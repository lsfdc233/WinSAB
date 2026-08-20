package com.winsab.winb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * winb - WinSAB 命令行启动器。
 *
 * 用法:
 *   winb.exe -run <exe路径> [参数...]            以普通权限运行指定程序
 *   winb.exe -run--admin <exe路径> [参数...]     以管理员身份运行指定程序
 *
 * 特性:
 *   - 支持相对路径;
 *   - 若目标程序就是 winb 自身 (同一 exe 文件), 会递归启动自身形成循环;
 *   - 若目标只是 winb 的另一份副本 (不同文件), 则只正常启动一次, 不会循环。
 *
 * 启动时读取打包在 JAR 内的 OUTPUT.txt:
 *   第一行是程序名称, 用 figlet Standard 字体 (同 BackPackManager 横幅) 渲染为
 *   ASCII 艺术字; 其余行原样打印。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        setupStdout();

        boolean admin = false;
        String exePath = null;
        List<String> targetArgs = new ArrayList<>();

        for (String arg : args) {
            if (exePath == null) {
                switch (arg) {
                    case "-run" -> {
                        // 普通模式: 等待后续路径参数
                    }
                    case "-run--admin" -> admin = true;
                    case "--admin" -> admin = true;
                    case "-h", "--help", "-?" -> {
                        printUsage();
                        return;
                    }
                    default -> exePath = stripQuotes(arg); // 第一个非选项参数即为 exe 路径
                }
            } else {
                // 路径之后的参数原样传递给目标程序
                targetArgs.add(arg);
            }
        }

        printBanner();

        if (exePath == null) {
            // 完整用法已包含在 OUTPUT.txt 的横幅输出中, 这里不再重复打印
            System.exit(2);
        }

        int code = run(exePath, admin, args, targetArgs);
        System.out.println();
        System.out.println("退出码: " + code);
        System.exit(code);
    }

    /** 去掉路径参数首尾成对的单引号或双引号 (兼容 cmd 中直接输入引号的写法) */
    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '\'' || first == '"') && first == last) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static void printUsage() {
        System.out.println("用法:");
        System.out.println("  winb.exe -run <exe路径> [参数...]           以普通权限运行指定程序");
        System.out.println("  winb.exe -run--admin <exe路径> [参数...]    以管理员身份运行指定程序");
        System.out.println("  winb.exe -h | --help                        显示本帮助");
        System.out.println("说明:");
        System.out.println("  <exe路径> 之后的参数原样传递给目标程序; --admin 需写在路径之前。");
        System.out.println("  支持相对路径; 若目标就是 winb 自身, 会不断新开独立的 winb 终端窗口");
        System.out.println("  (无限循环); 若目标只是 winb 的副本 (另一份 exe), 则只启动一次, 不会循环。");
    }

    /** 强制 System.out 以 UTF-8 输出, 并把真实控制台代码页切到 UTF-8 */
    private static void setupStdout() {
        try {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 保持默认输出流
        }
        if (System.console() != null) {
            trySetConsoleUtf8();
        }
    }

    /** 将当前控制台代码页切换为 UTF-8 (65001), 失败时静默忽略 */
    private static void trySetConsoleUtf8() {
        try {
            new ProcessBuilder("cmd.exe", "/c", "chcp 65001 >nul")
                    .inheritIO()
                    .start()
                    .waitFor();
        } catch (Exception ignored) {
            // 非真实控制台或权限不足时忽略
        }
    }

    /** 读取打包在资源中的 OUTPUT.txt (UTF-8) */
    private static List<String> readOutputTxt() {
        List<String> lines = new ArrayList<>();
        try (InputStream in = Main.class.getResourceAsStream("/OUTPUT.txt")) {
            if (in == null) {
                System.out.println("[警告] 找不到内置的 OUTPUT.txt");
                return lines;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        } catch (IOException e) {
            System.out.println("[警告] 读取 OUTPUT.txt 失败: " + e.getMessage());
        }
        return lines;
    }

    /** 第一行程序名 -> figlet ASCII 艺术字; 其余行原样打印 */
    private static void printBanner() {
        List<String> lines = readOutputTxt();
        if (lines.isEmpty()) {
            return;
        }
        System.out.println(Figlet.render(lines.get(0).trim()));
        System.out.println();
        for (int i = 1; i < lines.size(); i++) {
            System.out.println(lines.get(i));
        }
    }

    private static int run(String exePath, boolean admin, String[] originalArgs, List<String> targetArgs) {
        File target = new File(exePath);
        if (!target.isFile()) {
            System.out.println("[错误] 找不到可执行文件: " + target.getAbsolutePath());
            return 1;
        }

        File self = canonicalOrNull(selfExePath());
        File tgt = canonicalOrNull(target.getAbsolutePath());

        // 目标就是 winb 自身 (同一 exe 文件) -> 递归启动循环 (每个新实例新开一个终端窗口)
        if (self != null && tgt != null && self.getPath().equalsIgnoreCase(tgt.getPath())) {
            return runSelfLoop(self, originalArgs);
        }

        File workDir = tgt.getParentFile();
        System.out.println("正在" + (admin ? "以管理员身份" : "") + "运行: " + tgt.getPath()
                + (workDir != null ? " (工作目录: " + workDir.getPath() + ")" : ""));
        try {
            if (admin) {
                return runElevated(tgt, targetArgs);
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(tgt.getPath());
            cmd.addAll(targetArgs);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null) {
                // 子进程工作目录 = 目标 exe 所在目录 (像双击运行一样, 相对参数在 exe 目录下解析)
                pb.directory(workDir);
            }
            pb.inheritIO();
            Process process = pb.start();
            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.out.println("[错误] 启动失败: " + e.getMessage());
            return 1;
        }
    }

    /** 自运行循环的扇出数: 每个实例并行新开 2 个子窗口, 窗口数每代翻倍 (指数级) */
    private static final int SELF_LOOP_FANOUT = 2;

    /**
     * 递归启动自身: 通过 cmd start 并行新开 SELF_LOOP_FANOUT 个独立终端窗口,
     * 每个子实例收到相同参数后会再次判定目标为自身并各自新开 2 个窗口,
     * 窗口数量按 2 的幂指数增长。父窗口 /wait 等待所有子窗口结束后关闭。
     */
    private static int runSelfLoop(File self, String[] originalArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("cmd.exe");
        cmd.add("/c");
        cmd.add("start");
        cmd.add(""); // 空窗口标题 (start 会把第一个引号参数当作标题)
        cmd.add("/wait");
        cmd.add(self.getPath());
        for (String arg : originalArgs) {
            cmd.add(arg);
        }
        List<Process> procs = new ArrayList<>();
        try {
            for (int i = 0; i < SELF_LOOP_FANOUT; i++) {
                procs.add(new ProcessBuilder(cmd).start());
            }
            int code = 0;
            for (Process p : procs) {
                code = p.waitFor();
            }
            return code;
        } catch (IOException | InterruptedException e) {
            System.out.println("[错误] 递归启动失败: " + e.getMessage());
            return 1;
        }
    }

    /**
     * 获取当前 winb 可执行文件自身的完整路径。
     * 优先级: launch4j 注入的 exe.path 系统属性 ->
     *         启动本 JVM 的父进程 (即 winb.exe) 命令行 ->
     *         java.class.path。
     */
    private static String selfExePath() {
        String p = System.getProperty("exe.path");
        if (p != null && !p.isEmpty()) {
            return p;
        }
        Optional<String> parentCmd = ProcessHandle.current().parent().flatMap(h -> h.info().command());
        if (parentCmd.isPresent()) {
            String cmd = parentCmd.get();
            if (cmd.toLowerCase(Locale.ROOT).endsWith(".exe")
                    && !cmd.toLowerCase(Locale.ROOT).endsWith("java.exe")) {
                return cmd;
            }
        }
        return System.getProperty("java.class.path", "");
    }

    /** 通过 PowerShell Start-Process -Verb RunAs 以管理员身份运行 (会弹出 UAC 确认) */
    private static int runElevated(File exe, List<String> targetArgs) {
        StringBuilder script = new StringBuilder("Start-Process -FilePath '")
                .append(exe.getPath().replace("'", "''"))
                .append("'");
        String parent = exe.getParent();
        if (parent != null && !parent.isEmpty()) {
            // 与普通模式一致: 工作目录 = 目标 exe 所在目录
            script.append(" -WorkingDirectory '").append(parent.replace("'", "''")).append("'");
        }
        if (!targetArgs.isEmpty()) {
            script.append(" -ArgumentList ");
            for (int i = 0; i < targetArgs.size(); i++) {
                if (i > 0) {
                    script.append(',');
                }
                script.append('\'').append(targetArgs.get(i).replace("'", "''")).append('\'');
            }
        }
        script.append(" -Verb RunAs -Wait");
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script.toString());
        pb.inheritIO();
        try {
            Process process = pb.start();
            return process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.out.println("[错误] 提权启动失败: " + e.getMessage());
            return 1;
        }
    }

    private static File canonicalOrNull(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        try {
            return new File(path).getCanonicalFile();
        } catch (IOException e) {
            return new File(path).getAbsoluteFile();
        }
    }
}
