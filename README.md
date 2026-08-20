# WinSAB (winb)

在 Windows 上实现的 Wine 兼容层 (Java 实现) —— 命令行启动器 `winb.exe`。

## 构建

环境要求:

- JDK 21+
- Gradle 8.14+ (项目自带 `gradlew` wrapper)

```bash
# 构建 JAR + 生成 winb.exe (输出到 build/launch4j/winb.exe)
gradlew.bat build

# 构建并把 winb.exe 复制到 dist/ 目录
gradlew.bat distExe
```

> `winb.exe` 是 launch4j 生成的 Windows 控制台程序, JAR 已被内嵌进 EXE
> (含 `OUTPUT.txt` 与 figlet 字体 `standard.flf`)。运行需要本机已安装 JRE 21+。
>
> `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 指向腾讯云
> 镜像 (该网络环境无法访问 services.gradle.org 的 github 跳转); 如需官方源,
> 可改回 `https://services.gradle.org/distributions/gradle-8.14.3-bin.zip`。

## 用法

```
winb.exe -run <exe路径> [参数...]            以普通权限运行指定程序
winb.exe -run--admin <exe路径> [参数...]     以管理员身份运行指定程序 (弹出 UAC 确认)
winb.exe -run ./winb.exe                     新开终端窗口递归启动自身 (无限循环)
winb.exe -h | --help                         显示帮助
```

- `<exe路径>` 之后的参数会原样传递给目标程序;
- 支持相对路径 (相对当前工作目录解析), 也支持首尾成对的引号;
- **子进程工作目录 = 目标 exe 所在目录** (像双击运行一样: 相对参数在 exe
  目录下解析, 例如 `winb.exe -run D:\Containers\API\node.exe api.js` 时
  `api.js` 会在 `D:\Containers\API` 下查找, 而不是在启动 winb 的目录);
- `--admin` 需写在路径之前, 等价于 `-run--admin`;
- `-run--admin` 通过 PowerShell `Start-Process -Verb RunAs` 提权运行目标程序。

### 自身识别与递归循环

程序会解析目标的完整路径并与自身比较:

- 目标**就是 winb 自身** (同一 exe 文件, 例如 `winb.exe -run ./winb.exe`)
  → 每个实例都会**并行新开 2 个独立终端窗口**递归启动自身, 窗口数量每代
  翻倍 (**指数级增长**: 1 → 2 → 4 → 8 → 16 …), 每个窗口保留自己的横幅并等待子窗口;
- 目标**只是 winb 的一份副本** (另一份 exe 文件, 例如
  `winb.exe -run 'winb -副本.exe'`) → 只正常启动该副本一次, **不会**无限创建进程。

停止循环: `taskkill /f /im winb.exe`。由于指数繁衍时部分实例可能仍在
创建子窗口, 一次 taskkill 后可能还有少量窗口继续弹出, **请重复执行
2~3 次 taskkill**(或在任务管理器中结束全部 `winb.exe`)直到不再弹出。
`SELF_LOOP_FANOUT` 常量(`Main.java`)控制每个实例新开的窗口数(当前为 2)。

## 输出

程序启动时会打印打包在 JAR 内的 `OUTPUT.txt`:

- 第一行是程序名称, 用 **figlet "Standard" 字体** (与 BackPackManager 启动横幅同款,
  由内置的 `standard.flf` 解析渲染) 打印为 ASCII 艺术字;
- 其余行原样打印;

随后运行指定的 EXE, 等待其结束后打印退出码。输出强制为 UTF-8 (真实控制台会
自动 `chcp 65001`), 中文不会乱码。

## 项目结构

```
src/main/java/com/winsab/winb/Main.java    入口 (参数解析/自身识别/进程启动/提权)
src/main/java/com/winsab/winb/Figlet.java  迷你 figlet 渲染器 (复刻 pyfiglet smushing)
src/main/resources/OUTPUT.txt              随 JAR 打包的输出内容
src/main/resources/standard.flf            figlet Standard 字体
build.gradle                               构建脚本 (java + launch4j 插件)
```
