@echo off
REM benchmark-all.bat — Spring Web Benchmark 一键全量运行脚本 (Windows)
REM
REM 直接绕过 jmh-maven-plugin，使用 java -cp 运行 BenchmarkRunner。
REM 每个 scenario (接口) 单独 fork JVM，结果文件独立。
REM 用法:
REM   benchmark-all.bat                          默认: 全部 10 profile × 7 场景
REM   benchmark-all.bat --scenario jsonEcho      只跑指定场景
REM   benchmark-all.bat --scenarios a,b,c        跑指定多个场景
REM
REM 输出: benchmark-reports/{run-id}/report.md
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0

REM ========== 解析参数（必须在 RUN_ID 生成前，因为 SHIFT 会改变参数位置） ==========
set "SCENARIO_ARG="
set "SCENARIOS_ARG="

:parse_args
if "%~1"=="" goto args_done
if "%~1"=="--scenario" (
    set "SCENARIO_ARG=%~2"
    shift /1
    shift /1
    goto parse_args
)
if "%~1"=="--scenarios" (
    set "SCENARIOS_ARG=%~2"
    shift /1
    shift /1
    goto parse_args
)
if "%~1"=="--profiles" (
    REM ignored in .bat for simplicity, use --profiles in .sh on WSL
    shift /1
    shift /1
    goto parse_args
)
echo Usage: %0 [--scenario name^|--scenarios a,b,c]
exit /b 1
:args_done

REM ========== 生成 RUN_ID ==========
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value 2^>nul') do set "dt=%%I"
if "%dt%"=="" (
    set RUN_ID=%date:~0,4%%date:~5,2%%date:~8,2%-%time:~0,2%%time:~3,2%%time:~6,2%
    set RUN_ID=!RUN_ID: =0!
) else (
    set RUN_ID=%dt:~0,8%-%dt:~8,6%
)
set REPORTS_DIR=benchmark-reports

echo ==========================================
echo Spring Web Benchmark - %RUN_ID%
echo ==========================================

REM ========== 检测 JDK 版本 ==========
for /f "tokens=2 delims= " %%I in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JVER=%%I"
set "JVER=%JVER:"=%"
set "JDK_DIR=jdk-%JVER%"
echo JDK: %JVER%

REM 判断 GC 日志格式
echo %JVER%|findstr /b "1." >nul 2>&1
if %errorlevel% equ 0 (set GC_MODE=jdk8) else (set GC_MODE=jdk11)

set RESULTS_DIR=%REPORTS_DIR%\%RUN_ID%\%JDK_DIR%
mkdir "%RESULTS_DIR%" 2>nul

REM ========== Step 1: 从根目录全量编译（安装依赖到本地仓库） ==========
echo.
echo [1/4] 全量编译所有模块...
pushd "%SCRIPT_DIR%\.."
call mvn clean install -DskipTests -q
if %errorlevel% neq 0 (
    echo [ERROR] 编译失败
    exit /b 1
)
popd
cd /d "%SCRIPT_DIR%"

REM ========== 定义 profile 列表 (name,port,class) ==========
set "P1=perf,9091,PerfBenchmark"
set "P2=perf-filter,9092,PerfFilterBenchmark"
set "P3=perf-support,9093,PerfSupportBenchmark"
set "P4=perf-support-filter,9094,PerfSupportFilterBenchmark"
set "P5=tomcat,9101,TomcatBenchmark"
set "P6=tomcat-filter,9102,TomcatFilterBenchmark"
set "P7=undertow,9111,UndertowBenchmark"
set "P8=undertow-filter,9112,UndertowFilterBenchmark"
set "P9=webflux,9121,WebFluxBenchmark"
set "P10=webflux-filter,9122,WebFluxFilterBenchmark"
set PROFILES=%P1% %P2% %P3% %P4% %P5% %P6% %P7% %P8% %P9% %P10%

REM ========== 定义场景列表 ==========
set "S1=jsonEcho"
set "S2=helloGet"
set "S3=asyncDeferredResult"
set "S4=bytes"
set "S5=validatePost"
set "S6=jsonEchoLarge"
set "S7=largeResponse"
set "S8=sseStream"
set ALL_SCENARIOS=%S1% %S2% %S3% %S4% %S5% %S6% %S7% %S8%

REM 确定要执行的 scenarios
if not "%SCENARIO_ARG%"=="" (
    set SCENARIOS_TO_RUN=%SCENARIO_ARG%
) else if not "%SCENARIOS_ARG%"=="" (
    for %%S in (%SCENARIOS_ARG%) do set SCENARIOS_TO_RUN=!SCENARIOS_TO_RUN! %%S
) else (
    set SCENARIOS_TO_RUN=%ALL_SCENARIOS%
)

REM 计算场景数
set SCENARIO_COUNT=0
for %%S in (%SCENARIOS_TO_RUN%) do set /a SCENARIO_COUNT+=1

REM ========== Step 2+3: 逐个 profile 编译 → classpath → 逐个 scenario 运行 ==========
echo.
echo [2/4] 逐个 profile 编译、构建 classpath 并按 scenario 运行...
set COUNT=0

for %%P in (%PROFILES%) do (
    for /f "tokens=1,2,3 delims=," %%A in ("%%P") do (
        set PROFILE=%%A
        set PORT=%%B
        set BENCH_CLASS=%%C

        REM 编译（clean compile 确保 JMH 桩代码正确生成）
        echo   [!PROFILE!] compile...
        call mvn -Pbenchmark-!PROFILE! clean compile -q
        if !errorlevel! neq 0 (
            echo   --^> COMPILE FAIL
            goto :eof
        )

        REM 构建 classpath
        echo   [!PROFILE!] classpath...
        call mvn -Pbenchmark-!PROFILE! dependency:build-classpath -Dmdep.outputFile=target\cp-!PROFILE!.txt -q

        REM 读取 classpath（在场景循环外读取一次）
        set /p CP=<target\cp-!PROFILE!.txt

        REM 逐个 scenario 运行
        for %%S in (%SCENARIOS_TO_RUN%) do (
            set /a COUNT+=1
            set SCENARIO=%%S
            set "SCENARIO_PROFILE=!PROFILE!-!SCENARIO!"

            echo   [!COUNT!/?] !SCENARIO_PROFILE! (port !PORT!)

            REM GC 日志参数（文件名含 scenario）
            if !GC_MODE!==jdk8 (
                set "GC_ARG=-XX:+PrintGCDetails -Xloggc:%CD%\!RESULTS_DIR!\gc-!PROFILE!-!SCENARIO!.log -XX:+PrintGCDateStamps"
            ) else (
                set "GC_LOG_PATH=%CD%\!RESULTS_DIR!\gc-!PROFILE!-!SCENARIO!.log"
                set "GC_LOG_PATH=!GC_LOG_PATH:\=/!"
                set "GC_ARG=-Xlog:gc*=info:file=!GC_LOG_PATH!:time,uptime,level,tags"
            )

            REM 端口可用性检查（防止上一进程残留导致 BindException）
            netstat -ano 2>nul | findstr /C:":!PORT! " >nul 2>&1
            if !errorlevel! equ 0 (
                echo     [WARN] Port !PORT! ^(PROFILE=!PROFILE!^) is already in use.
                echo     [WARN] Benchmark may fail with BindException.
            )

            REM 运行基准测试（fork=1 隔离 JVM，只匹配当前 scenario）
            echo     [benchmark] !SCENARIO_PROFILE!...
            java -cp "target/classes;!CP!" ^
                -Djmh.forks=1 ^
                -Dbenchmark.port=!PORT! ^
                -Dbenchmark.profile.name=!SCENARIO_PROFILE! ^
                -Dbenchmark.output.dir=%CD%\!RESULTS_DIR! ^
                -Dbenchmark.gc.log.arg="!GC_ARG!" ^
                -Dbenchmark.include=".*!BENCH_CLASS!\.!SCENARIO!$" ^
                io.springperf.benchmark.BenchmarkRunner

            if errorlevel 1 (echo     --^> FAIL) else (echo     --^> SUCCESS)
        )
    )
)

REM ========== Step 4: 生成报告 ==========
echo.
echo [4/4] 生成报告...
if exist target\cp-report.txt del target\cp-report.txt
call mvn dependency:build-classpath -Dmdep.outputFile=target\cp-report.txt -q 2>nul
if exist target\cp-report.txt (
    set /p CP_REPORT=<target\cp-report.txt
    java -cp "target/classes;!CP_REPORT!" io.springperf.benchmark.report.generator.ReportGenerator "%CD%\%REPORTS_DIR%"
) else (
    java -cp "target/classes" io.springperf.benchmark.report.generator.ReportGenerator "%CD%\%REPORTS_DIR%"
)

echo.
echo ==========================================
echo 完成!
echo 报告: %CD%\%REPORTS_DIR%\%RUN_ID%\report.md
echo ==========================================
endlocal
