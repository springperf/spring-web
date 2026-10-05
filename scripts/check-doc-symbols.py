#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check-doc-symbols.py — 校验契约文档里点名的**符号**在代码里确实存在。

用法:
    python3 scripts/check-doc-symbols.py              # 门禁：R1/R2/R3/R4 全部拦截
    python3 scripts/check-doc-symbols.py --self-test  # 自检：用合成符号表验证各规则
    python3 scripts/check-doc-symbols.py --quiet      # 只输出汇总
    python3 scripts/check-doc-symbols.py -v           # 列出每个文件与白名单规模

退出码: 0 = 通过；1 = 存在缺陷；2 = 用法/环境错误

门禁规则（只查「符号是否存在」，**不查句子真假**——叙述性断言必须人工回代码求证）:
    R1 配置键：形如 `spring.*` / `server.*` / `pool.*` / `management.*` 的键，必须能在本仓源码中
       核验到（键是"我们自己的"，写错即为缺陷）。核验覆盖三种真实读法：整串字面量、前缀字面量
       拼接（`"server.ssl."` + `"enabled"`）、多段部分键拼接（`"management.endpoints.web.exposure"`
       + `".include"`）——只认整串会把前两种误判为缺陷（实测 34 条全是这个原因）。通配写法、
       FAMILY_PREFIXES（家族前缀，精确匹配）与 EXTERNAL_KEYS（有意引用的 Boot 键）不查。
    R2 未知类名：≥2 个驼峰段、以已知框架后缀结尾、且**不属于本仓**的类名。这条在 2026-09 由
       "只报告"升为**门禁**，代价是 EXTERNAL_TYPES 那 80 余条**带来源**的白名单：升门禁的依据是
       它抓到过真的缺陷（文档把 `ModelContext` 写成 `ModelSupport`，并把所在模块写成另一个模块）。
       归属未知的 `Class.member` 也走这条（见下）。
    R3 疑似错拼：≥2 个驼峰段、与本仓某类名**共享首个驼峰段**、编辑距离为 1 的标识符
       （DispatcherHandlers -> DispatcherHandler）。共享首段是刻意的约束，用来排除
       IWebContext -> WebContext 这类"别家命名"的误报。
    R4 成员：形如 `Class.member` / `Class#member`，若 Class 是本仓类，则 member 必须在源码中
       出现过（同名成员存在于别处也算通过——见"已知局限"）；若 Class 在 EXTERNAL_TYPES 里，跳过
       （归属不在本仓）；若 Class **谁也不认识**，按 R2 报出——这正是 ModelSupport 那次的盲区
       （它写成 `ModelSupport.getOrCreate()`，只报告未知类名的版本看不见它）。

按文件的规则豁免（写在代码里，而不是靠白名单堆）:
    docs/BREAKING-CHANGES.md 与 docs/(en/)changelog.md 的职责就是列出**已移除/已改名**的键与成员
    （server.async.timeout、NettyServerHttpResponse.CONN_CTX…），所以它们不参与 R1/R2/R3/R4；
    CONTRIBUTING.md 的 japicmp 段落同理记录已移除成员，故那里不查 R4。

扫描范围: 契约文档——仓库根的 README.md / CONTRIBUTING.md、docs/*.md 与 docs/en/*.md。
    docs/internals/**、docs/feature/**、docs/memory/**、.agent/** 属内部笔记或历史材料，
    出现旧 API 名、Spring/Netty 类型名是正常的，不纳入（否则门禁会被噪声淹没）。

已知局限（写在这里是为了不夸大它的能力）:
    - 只看**存在性**：`getURI() 在畸形 authority 下抛异常` 这类句子它一律看不出对错。
    - R4 只要求成员在**全仓**出现过，不校验归属（`A.method` 写成 `B.method` 时，若 B 恰好
      也有同名成员则放行）。要收紧需解析 Java，成本远大于收益。
    - 全限定名（`io.springperf.web.view.ViewResolver`）不做首段校验，只从 token 形态上跳过。
    - 白名单是**显式**的：新增外部符号时在 EXTERNAL_TYPES / EXTERNAL_KEYS 里加一条并写明来源，
      而不是放宽规则（与 CONTRIBUTING 里"豁免要写明依据"的约定一致）。
"""

import argparse
import os
import re
import subprocess
import sys

# ---------------- 规则数据 ----------------

# R1：这些前缀下的键由本仓定义，文档写错即为缺陷
KEY_PREFIXES = ('spring.', 'server.', 'pool.', 'management.')

# R1 白名单：我们**有意引用**的 Boot/Tomcat 键（用于说明差异），本仓不定义它们。
# 加条目时须能在 spring-boot-autoconfigure 的 spring-configuration-metadata.json 里查到。
EXTERNAL_KEYS = {
    'server.tomcat.max-part-count': 'Boot/Tomcat',
    'server.tomcat.max-part-header-size': 'Boot/Tomcat',
    'server.tomcat.max-connections': 'Boot/Tomcat',
    'server.tomcat.connectionTimeout': 'Tomcat',
    'spring.freemarker.charset': 'Boot',
    # 由 Boot 的 @ConfigurationProperties 类消费（本仓只是注入使用），故本仓源码里没有字面量。
    # 依据：ActuatorEndpointAutoConfiguration 注入 WebEndpointProperties（前缀 management.endpoints.web）。
    'management.endpoints.web.base-path': 'Boot WebEndpointProperties',
}

# 仅作"家族前缀"出现的写法（`server.http.*` 之类），不是具体键。**精确匹配**才跳过，
# 因此 `server.http.typo-key` 仍会被检查（见自检用例）。
FAMILY_PREFIXES = ('server.http', 'server.ssl', 'server.servlet', 'server.servlet.context-parameters',
                   'spring.web', 'spring.mvc', 'spring.servlet', 'management.server',
                   'management.endpoints', 'management.endpoints.web', 'pool')

# R2：类名候选的后缀（框架同族命名）。
CLASS_SUFFIXES = (
    'Adapter', 'Aggregator', 'Chain', 'Codec', 'Config', 'Configuration', 'Constant', 'Constants',
    'Context', 'Controller', 'Converter', 'Decoder', 'Dispatcher', 'Encoder', 'Exception', 'Factory',
    'Filter', 'Handler', 'Holder', 'Initializer', 'Interceptor', 'Invoker', 'Listener', 'Loader',
    'Manager', 'Mapper', 'Matcher', 'Optimizer', 'Pool', 'Processor', 'Provider', 'Registry',
    'Request', 'Resolver', 'Response', 'Router', 'Server', 'Session', 'Storage', 'Support',
    'Template', 'Validator', 'View', 'Wrapper',
)

# R2 白名单：文档里合法出现、但**不属于本仓**的类名。每条都带来源，来源来自三种证据：
#   (a) 文档代码块里**定义了它**（示例类）—— 用 `class X` 在 *.md 里检索确认；
#   (b) 本仓主代码**import 了它**（外部依赖）—— 记为包名；
#   (c) 本地 Maven 仓库的 jar 里**含该类**（外部依赖）—— 记为包名。
# 同名碰撞要按**文档语境**标注（实测踩过三处）：ServiceLoader 命中的是 surefire 的同名类而文档指
# java.util；ValidationException 命中 packageurl 而文档指应用自定义异常；ExceptionHandler 的 import
# 证据来自 com.lmax.disruptor 而文档指的是 Spring 注解。另有 HandlerExecutionChain 在任何本地 jar 里
# 都找不到，但它确实是 Spring MVC 的（spring-webmvc 不在本机仓库）——"没找到 ≠ 不存在"。
# 新增条目必须写明来源；宁可加一条，也不要放宽规则。
EXTERNAL_TYPES = {
    # (a) 文档代码块里定义的示例类（用户代码，不是框架 API）。若某条将来升级为框架类，请从此处移除。
    'AsyncMonitorInterceptor': 'docs example', 'CorsConfig': 'docs example',
    'CrossOriginController': 'docs example', 'CryptoInterceptor': 'docs example',
    'CurrentUserResolverProvider': 'docs example', 'CustomAnnotationResolverProvider': 'docs example',
    'CustomExceptionResolver': 'docs example', 'CustomFilter': 'docs example',
    'CustomInterceptor': 'docs example', 'CustomJsonConverter': 'docs example',
    'CustomReturnValueResolver': 'docs example', 'CustomRouterOptimizer': 'docs example',
    'HeavyController': 'docs example', 'HelloController': 'docs example',
    'LogInterceptor': 'docs example', 'MarkdownViewResolver': 'docs example',
    'MyExchangeProvider': 'docs example', 'MyViewResolver': 'docs example',
    'MyWsConfig': 'docs example', 'ReactiveController': 'docs example',
    'ResourceConfig': 'docs example', 'ThreadPoolConfig': 'docs example',
    'XmlHttpBodyConverter': 'docs example', 'XmlSpringConverter': 'docs example',
    'AuthInterceptor': 'docs example', 'BatchHandler': 'docs example',
    'NotFoundException': 'docs example（应用自定义异常）',
    'ValidationException': 'docs example（应用自定义异常）',
    # 已废弃名：文档与台账里**作为历史记录**出现（"原名 X，已改名 Y"），不是现行主张。
    'ModelSupport': '已废弃名（现为 spring-web core 的 ModelContext）',
    # (b) 本仓主代码 import 的外部依赖（包名即来源）
    'AnnotationConfigApplicationContext': 'org.springframework.context.annotation',
    'AntPathMatcher': 'org.springframework.util',
    'CallableProcessingInterceptor': 'org.springframework.web.context.request.async',
    'ChunkedWriteHandler': 'io.netty.handler.stream',
    'DecoderException': 'io.netty.handler.codec',
    'DefaultCorsProcessor': 'org.springframework.web.cors',
    'DeferredResultProcessingInterceptor': 'org.springframework.web.context.request.async',
    'ObjectMapper': 'com.fasterxml.jackson.databind',
    'RequestDispatcher': 'jakarta.servlet',
    'ExceptionHandler': 'org.springframework.web.bind.annotation（注解；本地同名类属 disruptor）',
    'FullHttpRequest': 'io.netty.handler.codec.http',
    'GenericHttpMessageConverter': 'org.springframework.http.converter',
    'GenericTypeResolver': 'org.springframework.core',
    'HandlerMethodArgumentResolver': 'org.springframework.web.method.support',
    'HandlerMethodReturnValueHandler': 'org.springframework.web.method.support',
    'HttpMessageConverter': 'org.springframework.http.converter',
    'HttpRequest': 'io.netty.handler.codec.http',
    'HttpServerCodec': 'io.netty.handler.codec.http',
    'HttpServletRequest': 'jakarta.servlet.http',
    'HttpServletResponse': 'jakarta.servlet.http',
    'HttpSession': 'jakarta.servlet.http',
    'IWebContext': 'org.thymeleaf.context',
    'LocaleContextHolder': 'org.springframework.context.i18n',
    'MessageCodesResolver': 'org.springframework.validation',
    'MultiValueMapAdapter': 'org.springframework.util',
    'ReactiveAdapterRegistry': 'org.springframework.core',
    'RejectedExecutionException': 'java.util.concurrent',
    'RequestContextHolder': 'org.springframework.web.context.request',
    'ResponseStatusException': 'org.springframework.web.server',
    'RestController': 'org.springframework.web.bind.annotation',
    'SecurityFilterChain': 'org.springframework.security.web',
    'ServerHttpRequest': 'org.springframework.http.server',
    'ServerHttpResponse': 'org.springframework.http.server',
    'WebSocketHandler': 'org.springframework.web.socket',
    'WebSocketSession': 'org.springframework.web.socket',
    # (c) 其余外部类型（jar 取证或按文档语境判定）
    'AnnotationConfigServletWebServerApplicationContext': 'org.springframework.boot.web.servlet.context',
    'AcceptHeaderLocaleResolver': 'org.springframework.web.servlet.i18n',
    'LocaleResolver': 'org.springframework.web.servlet',
    'ChannelInboundHandler': 'io.netty.channel',
    'ContentNegotiationManager': 'org.springframework.web.accept',
    'DataBufferFactory': 'org.springframework.core.io.buffer',
    'DataSize': 'org.springframework.util.unit',
    'DefaultFullHttpResponse': 'io.netty.handler.codec.http',
    'FullHttpResponse': 'io.netty.handler.codec.http',
    'HandlerExecutionChain': 'org.springframework.web.servlet（本地无 spring-webmvc，jar 查不到）',
    'HttpObjectAggregator': 'io.netty.handler.codec.http',
    'HttpPostRequestDecoder': 'io.netty.handler.codec.http',
    'HttpResponseStatus': 'io.netty.handler.codec.http',
    'IllegalArgumentException': 'java.lang',
    'IllegalStateException': 'java.lang',
    'InnerOperator': 'reactor.core.publisher',
    'RoutingContext': 'io.vertx.ext.web（对比表里提到的其他框架）',
    'SerializableTypeWrapper': 'org.springframework.core',
    'ServiceLoader': 'java.util（本地同名类属 surefire）',
    # 以下三条只出现在 docs/overview.md 的 JFR 热点表里（`Class.method` 形式），本仓并未导入它们；
    # 来源按包名写，读者可在对应依赖里核对。
    'ByteToMessageDecoder': 'io.netty.handler.codec（JFR 热点帧，非本仓导入）',
    'HttpObjectDecoder': 'io.netty.handler.codec.http（JFR 热点帧，非本仓导入）',
    'SpringValidatorAdapter': 'org.springframework.validation.beanvalidation（JFR 热点帧，非本仓导入）',
    'TooLongFrameException': 'io.netty.handler.codec.http',
    'WebExceptionHandler': 'org.springframework.web.server',
    # JDK：会话反序列化过滤器的规格类型（手册在 server.servlet.session.persistent-deserialization-filter 行点名它）
    'ObjectInputFilter': 'java.io',
}

GATING_RULES = ('R1-config-key', 'R2-unknown-class', 'R3-likely-typo', 'R4-member')

# 按文件的规则豁免（见文件头"按文件的规则豁免"）
FILE_RULE_SKIPS = {
    'docs/BREAKING-CHANGES.md': ('R1-config-key', 'R2-unknown-class', 'R3-likely-typo', 'R4-member'),
    'docs/changelog.md': ('R1-config-key', 'R2-unknown-class', 'R3-likely-typo', 'R4-member'),
    'docs/en/changelog.md': ('R1-config-key', 'R2-unknown-class', 'R3-likely-typo', 'R4-member'),
    'CONTRIBUTING.md': ('R4-member',),
}

STRING_LITERAL = re.compile(r'"([a-z][a-z0-9-]*(?:\.[a-z0-9-]+)+)"')
# 以 `.` 结尾的前缀字面量（"server.ssl."）：STRING_LITERAL 要求末段非空，故单独一条。
PREFIX_LITERAL = re.compile(r'"([a-z][a-z0-9-]*(?:\.[a-z0-9-]+)*\.)"')
INLINE_CODE = re.compile(r'`([^`\n]+)`')
FENCE = re.compile(r'^\s*(```|~~~)')
FQN = re.compile(r'^\w+(?:\.\w+)*\.[A-Z]\w*$')   # io.springperf.web.view.ViewResolver


def humps(token):
    """驼峰段数：FooBar -> 2，foo -> 0。"""
    return len(re.findall(r'[A-Z]', token))


def has_suffix(token):
    return token.endswith(CLASS_SUFFIXES)


def edit_distance_one(a, b):
    """a、b 是否编辑距离恰好为 1（等长单字符替换 / 单字符增删）。"""
    if a == b or abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        return sum(1 for x, y in zip(a, b) if x != y) == 1
    short, long_ = (a, b) if len(a) < len(b) else (b, a)
    i = j = 0
    skipped = False
    while i < len(short) and j < len(long_):
        if short[i] != long_[j]:
            if skipped:
                return False
            skipped = True
            j += 1
            continue
        i += 1
        j += 1
    return True


class Repo:
    """本仓可核验的符号表（self-test 用合成数据构造同一个类）。"""

    def __init__(self, classes, texts, keys, prefixes=(), partials=()):
        self.classes = classes      # name -> relpath
        self.texts = texts          # relpath -> source
        self.keys = keys            # 完整键（字符串字面量）
        self.prefixes = prefixes    # 以 `.` 结尾的前缀字面量，如 "server.ssl."
        self.partials = partials    # 多段字面量，如 "management.endpoints.web.exposure"

    def member_exists(self, member):
        return any(re.search(r'\b%s\b' % re.escape(member), t) for t in self.texts.values())

    def is_known_key(self, key):
        """键是否可核验：完整字面量、前缀字面量加后缀、或"多段部分键 + 后缀"。

        后两种对应主代码里的拼接读法（`"server.ssl."` + `"enabled"`、
        `"management.endpoints.web.exposure"` + `".include"`），只靠整串字面量是看不见的。
        """
        return (key in self.keys
                or any(key.startswith(p) for p in self.prefixes)
                or any(key.startswith(p + '.') for p in self.partials))


def in_scope(rel):
    """契约文档：仓库根的两个文件 + docs/*.md 与 docs/en/*.md（见文件头"扫描范围"）。"""
    return rel in ('README.md', 'CONTRIBUTING.md') or bool(re.fullmatch(r'docs/(en/)?[^/]+\.md', rel))


def collect_repo(files_java):
    classes, texts, keys, prefixes, partials = {}, {}, set(), set(), set()
    for rel in files_java:
        try:
            src = open(rel, encoding='utf-8', errors='replace').read()
        except OSError:
            continue
        classes.setdefault(os.path.basename(rel)[:-5], rel)
        texts[rel] = src
        prefixes.update(PREFIX_LITERAL.findall(src))    # "server.ssl."：拼接前缀
        for lit in STRING_LITERAL.findall(src):
            keys.add(lit)
            if lit.count('.') >= 2:
                partials.add(lit)                       # "management.endpoints.web.exposure"
    return Repo(classes, texts, keys, prefixes, partials)


def code_spans(text):
    """产出 (行号, 代码片段)：行内反引号 + 围栏代码块内的整行。"""
    in_fence = False
    for i, line in enumerate(text.split('\n'), 1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            yield i, line
            continue
        for m in INLINE_CODE.finditer(line):
            yield i, m.group(1)


def check_text(rel, text, repo):
    """返回该文件的缺陷列表 [(rule, lineno, detail)]（已应用按文件的规则豁免）。"""
    skip = FILE_RULE_SKIPS.get(rel, ())
    out = []
    for lineno, span in code_spans(text):
        wildcard = '*' in span
        for token in re.split(r'[^A-Za-z0-9_.#-]+', span):
            token = token.rstrip('.')
            if not token or FQN.match(token):
                continue
            # R1 配置键
            if token.startswith(KEY_PREFIXES) and '.' in token:
                if (not wildcard and token not in EXTERNAL_KEYS and token not in FAMILY_PREFIXES
                        and not repo.is_known_key(token)):
                    out.append(('R1-config-key', lineno, token))
                continue
            # R4 类.成员；归属未知的成员按"未知类名"报出（ModelSupport.getOrCreate 的教训）
            m = re.fullmatch(r'([A-Z]\w*)[.#]([a-zA-Z_]\w*)', token)
            if m:
                if m.group(1) in repo.classes:
                    if not repo.member_exists(m.group(2)):
                        out.append(('R4-member', lineno, token))
                    continue
                if m.group(1) in EXTERNAL_TYPES:
                    continue          # 外部类型的成员：归属不在本仓，不查
                token = m.group(1)    # 归属未知：落到下面的类名检查
            # 类名候选：必须是大写开头、≥2 驼峰段、且不属于本仓、也不在白名单
            if (not token[:1].isupper() or humps(token) < 2
                    or token in repo.classes or token in EXTERNAL_TYPES):
                continue
            stem = re.match(r'[A-Z][a-z0-9]+', token)
            close = [c for c in repo.classes
                     if stem and c.startswith(stem.group(0)) and edit_distance_one(token, c)]
            if close:
                out.append(('R3-likely-typo', lineno, '%s (did you mean %s?)' % (token, sorted(close)[0])))
            elif has_suffix(token):
                out.append(('R2-unknown-class', lineno, token))
    return [f for f in out if f[0] not in skip]


# ---------------- 自检 ----------------

def self_test():
    """用合成符号表验证各规则（不读仓库）。"""
    repo = Repo(
        classes={'RealHandler': 'a/RealHandler.java', 'RealConfig': 'a/RealConfig.java'},
        texts={'a/RealHandler.java': 'class RealHandler { void handle() {} }',
               'a/RealConfig.java': 'class RealConfig { static final String K = "spring.x.y"; }'},
        keys={'spring.x.y'},
        prefixes=('server.ssl.',),
        partials=('management.endpoints.web.exposure',),
    )
    # 期望格式: (门禁命中规则, 非门禁命中规则)——当前四类规则都拦，故后者恒为空
    cases = {
        'R1 hit': ('use `spring.x.y`', [], []),
        'R1 miss': ('use `spring.nope.key`', ['R1-config-key'], []),
        'R1 wildcard skipped': ('use `spring.nope.*`', [], []),
        'R1 family prefix exact': ('use `server.ssl` and `management.endpoints.web`', [], []),
        'R1 prefix literal': ('use `server.ssl.enabled`', [], []),
        'R1 partial literal': ('use `management.endpoints.web.exposure.include`', [], []),
        'R1 real key under family is checked': ('use `server.http.typo-key`', ['R1-config-key'], []),
        'R2 unknown class': ('see `GhostHandler`', ['R2-unknown-class'], []),
        'R2 allowlisted external': ('see `LocaleContextHolder`', [], []),
        'R2 not a candidate: no suffix': ('see `macOS` and `SameSite`', [], []),
        'R2 not a candidate: lowercase': ('see `modelAndView`', [], []),
        'R3 typo': ('see `RealHandlers`', ['R3-likely-typo'], []),
        'R3 foreign name is not a typo': ('see `ForeignConfig`', ['R2-unknown-class'], []),
        'R4 hit': ('call `RealHandler.handle()`', [], []),
        'R4 miss': ('call `RealHandler.missing()`', ['R4-member'], []),
        'R4 external owner skipped': ('call `LocaleContextHolder.getLocaleContext()`', [], []),
        'R4 unknown owner falls through to R2': ('call `GhostHandler.build()`', ['R2-unknown-class'], []),
        'FQN skipped': ('see `io.example.web.RealHandlerX`', [], []),
    }
    bad = []
    for name, (text, want_gate, want_other) in cases.items():
        got = check_text('x.md', text, repo)
        g = [r for r, _, _ in got if r in GATING_RULES]
        o = [r for r, _, _ in got if r not in GATING_RULES]
        if g != want_gate or o != want_other:
            bad.append('%s: want %s/%s got %s/%s' % (name, want_gate, want_other, g, o))
    if check_text('docs/BREAKING-CHANGES.md', 'removed `spring.nope.key` and `GhostHandler`', repo):
        bad.append('per-file skip not applied')
    assert edit_distance_one('RealHandlers', 'RealHandler')
    assert not edit_distance_one('RealHandler', 'RealConfig')
    if bad:
        print('SELF-TEST FAILED')
        for b in bad:
            print('  ' + b)
        return 2
    print('self-test OK: %d cases across R1 (config key), R2 (unknown class, now gated), '
          'R3 (likely typo), R4 (member incl. unknown owner), FQN and per-file skips'
          % (len(cases) + 1))
    return 0


# ---------------- main ----------------

def main():
    ap = argparse.ArgumentParser(description='校验契约文档点名的符号在代码中确实存在')
    ap.add_argument('--self-test', action='store_true', help='运行自检（不读仓库）')
    ap.add_argument('--quiet', action='store_true', help='只输出汇总')
    ap.add_argument('-v', '--verbose', action='store_true', help='列出每个文件与白名单规模')
    ap.add_argument('--exclude', action='append', default=[], help='额外排除的路径前缀（可重复）')
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    def git_ls(*patterns):
        try:
            out = subprocess.run(['git', 'ls-files', *patterns], capture_output=True, text=True, check=True)
        except (OSError, subprocess.CalledProcessError) as e:
            print('错误: 需要 git 仓库（git ls-files 失败: %s）' % e, file=sys.stderr)
            sys.exit(2)
        return out.stdout.split()

    java = [f for f in git_ls('*.java') if '/src/main/java/' in f]
    markdown = [f for f in git_ls('*.md') if in_scope(f) and not f.startswith(tuple(args.exclude))]
    repo = collect_repo(java)

    findings = []
    for rel in markdown:
        try:
            text = open(rel, encoding='utf-8', errors='replace').read().replace('\r\n', '\n')
        except OSError:
            continue
        for rule, lineno, detail in check_text(rel, text, repo):
            findings.append((rule, rel, lineno, detail))
        if args.verbose:
            print('  checked: %s' % rel)

    if args.verbose:
        print('  gating rules: %s' % ', '.join(GATING_RULES))
        print('  allow-listed external keys: %d; external classes: %d'
              % (len(EXTERNAL_KEYS), len(EXTERNAL_TYPES)))

    if not args.quiet:
        for rule, rel, lineno, detail in findings:
            print('%-18s %s:%d  ->  %s' % (rule, rel, lineno, detail))

    print('scanned %d markdown files against %d source files: %d defect(s); '
          'external allow-list: %d keys + %d classes'
          % (len(markdown), len(java), len(findings), len(EXTERNAL_KEYS), len(EXTERNAL_TYPES)))
    return 1 if findings else 0


if __name__ == '__main__':
    try:
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    except AttributeError:
        pass
    sys.exit(main())
