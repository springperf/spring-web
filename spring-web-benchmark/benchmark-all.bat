@echo off
REM benchmark-all.bat — Spring Web Benchmark 一键全量运行脚本 (Windows)
REM
REM 直接绕过 jmh-maven-plugin，使用 java -cp 运行 BenchmarkRunner。
REM 输出: benchmark-reports/{run-id}/report.md
setlocal enabledelayedexpansion

set SCRIPT_DIR=%~dp0

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

REM ========== Step 2+3: 逐个 profile 编译 → 构建 classpath → 运行 ==========
echo.
echo [2/4] 逐个 profile 编译、构建 classpath 并运行...
set COUNT=0

for %%P in (%PROFILES%) do (
    for /f "tokens=1,2,3 delims=," %%A in ("%%P") do (
        set /a COUNT+=1
        set PROFILE=%%A
        set PORT=%%B
        set BENCH_CLASS=%%C

        echo [!COUNT!/10] !PROFILE! (port !PORT!)

        REM 编译（clean compile 确保 JMH 桩代码正确生成）
        echo   [compile] benchmark-!PROFILE!...
        call mvn -Pbenchmark-!PROFILE! clean compile -q
        if !errorlevel! neq 0 (
            echo   --^> COMPILE FAIL
            goto :eof
        )

        REM 构建 classpath
        echo   [classpath] benchmark-!PROFILE!...
        call mvn -Pbenchmark-!PROFILE! dependency:build-classpath -Dmdep.outputFile=target\cp-!PROFILE!.txt -q

        REM GC 日志参数
        if !GC_MODE!==jdk8 (
            set "GC_ARG=-XX:+PrintGCDetails -Xloggc:%CD%\!RESULTS_DIR!\gc-!PROFILE!.log -XX:+PrintGCDateStamps"
        ) else (
            set "GC_ARG=-Xlog:gc*=info:file=%CD%\!RESULTS_DIR!\gc-!PROFILE!.log:time,uptime,level,tags"
        )

        REM 读取 classpath
        set /p CP=<target\cp-!PROFILE!.txt

        REM 运行基准测试（fork=1 隔离 JVM）
        echo   [benchmark] !PROFILE!...
        java -cp "target/classes;!CP!" ^
            -Djmh.forks=1 ^
            -Dbenchmark.port=!PORT! ^
            -Dbenchmark.profile.name=!PROFILE! ^
            -Dbenchmark.output.dir=%CD%\!RESULTS_DIR! ^
            -Dbenchmark.gc.log.arg="!GC_ARG!" ^
            -Dbenchmark.include=".*!BENCH_CLASS!.*" ^
            io.springperf.benchmark.BenchmarkRunner

        if errorlevel 1 (echo   --^> FAIL) else (echo   --^> SUCCESS)
    )
)

REM ========== Step 4: 生成报告 ==========
echo.
echo [4/4] 生成报告...
REM 用最后一个 profile 的 classpath（通用依赖足够）
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