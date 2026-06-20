import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class Main {

    static class Job {
        int jobNumber;
        long pid;
        String command;
        Process process;

        Job(int jobNumber, long pid, String command, Process process) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }

    static class CommandSpec {
        String[] args;
        String stdoutFile;
        boolean appendStdout;
        String stderrFile;
        boolean appendStderr;
        String stdinFile;
    }

    public static void main(String[] args) throws Exception {
        List<Job> jobs = new ArrayList<>();
        Scanner sc = new Scanner(System.in);
        File currentDir = new File(System.getProperty("user.dir"));

        while (true) {
            reapJobs(jobs);
            System.out.print("$ ");
            if (!sc.hasNextLine())
                break;
            String s = sc.nextLine();

            if (s.equals("exit")) {
                break;
            }

            // ===================================================
            // PIPELINE ENGINE
            // ===================================================
            if (s.contains("|")) {
                List<String> stages = splitPipeline(s);
                InputStream prevOutput = null;
                Process lastExternalProcess = null;

                for (int i = 0; i < stages.size(); i++) {
                    String stageStr = stages.get(i).trim();
                    if (stageStr.isEmpty())
                        continue;

                    String[] rawParts = parseCommand(stageStr);
                    if (rawParts.length == 0)
                        continue;

                    CommandSpec spec = parseRedirection(rawParts);
                    if (spec.args.length == 0)
                        continue;

                    boolean isLast = (i == stages.size() - 1);
                    String cmd = spec.args[0];

                    if (isBuiltin(cmd)) {
                        String outStr = runBuiltin(spec.args, currentDir, jobs);

                        if (spec.stdoutFile != null) {
                            writeToFile(spec.stdoutFile, outStr, spec.appendStdout);
                            prevOutput = new ByteArrayInputStream(new byte[0]);
                        } else if (isLast) {
                            System.out.print(outStr);
                        } else {
                            prevOutput = new ByteArrayInputStream(outStr.getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        ProcessBuilder pb = new ProcessBuilder(spec.args);
                        pb.directory(currentDir);

                        if (spec.stdinFile != null) {
                            pb.redirectInput(new File(spec.stdinFile));
                        } else if (i == 0) {
                            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        }

                        if (spec.stdoutFile != null) {
                            File f = new File(spec.stdoutFile);
                            pb.redirectOutput(spec.appendStdout ? ProcessBuilder.Redirect.appendTo(f)
                                    : ProcessBuilder.Redirect.to(f));
                        } else if (isLast) {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }

                        if (spec.stderrFile != null) {
                            File f = new File(spec.stderrFile);
                            pb.redirectError(spec.appendStderr ? ProcessBuilder.Redirect.appendTo(f)
                                    : ProcessBuilder.Redirect.to(f));
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        Process p;
                        try {
                            p = pb.start();
                        } catch (IOException e) {
                            System.out.println(cmd + ": command not found");
                            prevOutput = new ByteArrayInputStream(new byte[0]);
                            continue;
                        }

                        if (i > 0 && prevOutput != null && spec.stdinFile == null) {
                            final InputStream source = prevOutput;
                            final OutputStream target = p.getOutputStream();
                            new Thread(() -> {
                                byte[] buf = new byte[1024];
                                int read;
                                try {
                                    while ((read = source.read(buf)) != -1) {
                                        target.write(buf, 0, read);
                                        target.flush();
                                    }
                                } catch (IOException ignored) {
                                } finally {
                                    try {
                                        target.close();
                                    } catch (IOException ignored) {
                                    }
                                }
                            }).start();
                        } else if (i > 0) {
                            try {
                                p.getOutputStream().close();
                            } catch (IOException ignored) {
                            }
                        }

                        if (!isLast && spec.stdoutFile == null) {
                            prevOutput = p.getInputStream();
                        } else {
                            prevOutput = new ByteArrayInputStream(new byte[0]);
                        }
                        lastExternalProcess = p;
                    }
                }

                if (lastExternalProcess != null) {
                    lastExternalProcess.waitFor();
                }
                continue;
            }

            // ===================================================
            // STANDALONE COMMAND EXECUTION
            // ===================================================
            String[] rawParts = parseCommand(s);
            boolean background = false;

            if (rawParts.length > 0 && rawParts[rawParts.length - 1].equals("&")) {
                background = true;
                rawParts = Arrays.copyOf(rawParts, rawParts.length - 1);
            }

            if (rawParts.length == 0)
                continue;

            CommandSpec spec = parseRedirection(rawParts);
            if (spec.args.length == 0)
                continue;

            String cmd = spec.args[0];

            if (isBuiltin(cmd)) {
                if (spec.stderrFile != null) {
                    writeToFile(spec.stderrFile, "", spec.appendStderr);
                }

                if (cmd.equals("cd")) {
                    String path = spec.args.length > 1 ? spec.args[1] : "~";
                    File target = path.equals("~") ? new File(System.getenv("HOME"))
                            : path.startsWith("/") ? new File(path) : new File(currentDir, path);

                    if (target.exists() && target.isDirectory()) {
                        currentDir = target.getCanonicalFile();
                    } else {
                        System.out.println("cd: " + path + ": No such file or directory");
                    }
                } else {
                    String outStr = runBuiltin(spec.args, currentDir, jobs);
                    if (spec.stdoutFile != null) {
                        writeToFile(spec.stdoutFile, outStr, spec.appendStdout);
                    } else {
                        System.out.print(outStr);
                    }
                }
            } else {
                boolean found = false;

                for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
                    File f = new File(dir, cmd);
                    if (f.exists() && f.canExecute()) {
                        ProcessBuilder pb = new ProcessBuilder(spec.args);
                        pb.directory(currentDir);

                        if (spec.stdinFile != null) {
                            pb.redirectInput(new File(spec.stdinFile));
                        } else {
                            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        }

                        if (spec.stdoutFile != null) {
                            File out = new File(spec.stdoutFile);
                            pb.redirectOutput(spec.appendStdout ? ProcessBuilder.Redirect.appendTo(out)
                                    : ProcessBuilder.Redirect.to(out));
                        } else {
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        }

                        if (spec.stderrFile != null) {
                            File err = new File(spec.stderrFile);
                            pb.redirectError(spec.appendStderr ? ProcessBuilder.Redirect.appendTo(err)
                                    : ProcessBuilder.Redirect.to(err));
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        Process process = pb.start();
                        if (background) {
                            int jobNumber = getNextJobNumber(jobs);
                            jobs.add(new Job(jobNumber, process.pid(), s, process));
                            System.out.println("[" + jobNumber + "] " + process.pid());
                        } else {
                            process.waitFor();
                        }
                        found = true;
                        break;
                    }
                }

                if (!found) {
                    System.out.println(cmd + ": command not found");
                }
            }
        }
        sc.close();
    }

    private static void writeToFile(String filename, String content, boolean append) throws IOException {
        Files.writeString(Path.of(filename), content, StandardOpenOption.CREATE,
                append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static CommandSpec parseRedirection(String[] parts) {
        CommandSpec spec = new CommandSpec();
        List<String> clean = new ArrayList<>();

        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if ((p.equals(">") || p.equals("1>")) && i + 1 < parts.length) {
                spec.stdoutFile = parts[i + 1];
                spec.appendStdout = false;
                i++;
            } else if ((p.equals(">>") || p.equals("1>>")) && i + 1 < parts.length) {
                spec.stdoutFile = parts[i + 1];
                spec.appendStdout = true;
                i++;
            } else if (p.equals("2>") && i + 1 < parts.length) {
                spec.stderrFile = parts[i + 1];
                spec.appendStderr = false;
                i++;
            } else if (p.equals("2>>") && i + 1 < parts.length) {
                spec.stderrFile = parts[i + 1];
                spec.appendStderr = true;
                i++;
            } else if (p.equals("<") && i + 1 < parts.length) {
                spec.stdinFile = parts[i + 1];
                i++;
            } else {
                clean.add(p);
            }
        }
        spec.args = clean.toArray(new String[0]);
        return spec;
    }

    private static List<String> splitPipeline(String input) {
        List<String> stages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\'' && !inDouble)
                inSingle = !inSingle;
            else if (c == '"' && !inSingle)
                inDouble = !inDouble;

            if (c == '|' && !inSingle && !inDouble) {
                stages.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        stages.add(current.toString());
        return stages;
    }

    private static String[] parseCommand(String input) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' && !inSingleQuote && !inDoubleQuote) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            } else if (c == '\\' && inDoubleQuote) {
                if (i + 1 < input.length()) {
                    char next = input.charAt(i + 1);
                    if (next == '"' || next == '\\') {
                        current.append(next);
                        i++;
                    } else {
                        current.append('\\');
                    }
                } else {
                    current.append('\\');
                }
            } else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (Character.isWhitespace(c) && !inSingleQuote && !inDoubleQuote) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0)
            args.add(current.toString());
        return args.toArray(new String[0]);
    }

    private static boolean isBuiltin(String cmd) {
        return cmd.equals("echo") || cmd.equals("type") || cmd.equals("exit") ||
                cmd.equals("pwd") || cmd.equals("cd") || cmd.equals("jobs");
    }

    private static String runBuiltin(String[] parts, File currentDir, List<Job> jobs) throws Exception {
        StringBuilder out = new StringBuilder();
        if (parts.length == 0)
            return "";

        switch (parts[0]) {
            case "echo":
                for (int i = 1; i < parts.length; i++) {
                    if (i > 1)
                        out.append(" ");
                    out.append(parts[i]);
                }
                out.append(System.lineSeparator());
                break;

            case "pwd":
                out.append(currentDir.getCanonicalPath()).append(System.lineSeparator());
                break;

            // --- THE UPGRADED JOBS BUILTIN ---
            case "jobs":
                List<Job> toRemove = new ArrayList<>();
                for (int i = 0; i < jobs.size(); i++) {
                    Job job = jobs.get(i);
                    char marker = (i == jobs.size() - 1) ? '+' : (i == jobs.size() - 2) ? '-' : ' ';

                    if (job.process.isAlive()) {
                        out.append(String.format("[%d]%c  %-24s%s%n", job.jobNumber, marker, "Running", job.command));
                    } else {
                        String cleanCmd = job.command.endsWith(" &")
                                ? job.command.substring(0, job.command.length() - 2)
                                : job.command;
                        out.append(String.format("[%d]%c  %-24s%s%n", job.jobNumber, marker, "Done", cleanCmd));
                        toRemove.add(job);
                    }
                }
                jobs.removeAll(toRemove);
                break;

            case "type":
                String target = parts[1];
                if (isBuiltin(target)) {
                    out.append(target).append(" is a shell builtin").append(System.lineSeparator());
                } else {
                    boolean found = false;
                    for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
                        File f = new File(dir, target);
                        if (f.exists() && f.canExecute()) {
                            out.append(target).append(" is ").append(f.getAbsolutePath())
                                    .append(System.lineSeparator());
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                        out.append(target).append(": not found").append(System.lineSeparator());
                }
                break;
        }
        return out.toString();
    }

    private static int getNextJobNumber(List<Job> jobs) {
        int n = 1;
        while (true) {
            boolean used = false;
            for (Job job : jobs) {
                if (job.jobNumber == n) {
                    used = true;
                    break;
                }
            }
            if (!used)
                return n;
            n++;
        }
    }

    private static void reapJobs(List<Job> jobs) {
        List<Job> deadJobs = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            if (!job.process.isAlive()) {
                char marker = (i == jobs.size() - 1) ? '+' : (i == jobs.size() - 2) ? '-' : ' ';
                String cmd = job.command.endsWith(" &") ? job.command.substring(0, job.command.length() - 2)
                        : job.command;
                System.out.printf("[%d]%c  %-24s%s%n", job.jobNumber, marker, "Done", cmd);
                deadJobs.add(job);
            }
        }
        jobs.removeAll(deadJobs);
    }
}