import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class Main {

    static class Job {
        int jobNumber;
        long pid;
        String command;
        Process process;
        // Removed 'doneReported' entirely!

        Job(int jobNumber, long pid, String command, Process process) {
            this.jobNumber = jobNumber;
            this.pid = pid;
            this.command = command;
            this.process = process;
        }
    }

    public static void main(String[] args) throws Exception {
        List<Job> jobs = new ArrayList<>();
        Scanner sc = new Scanner(System.in);
        File currentDir = new File(System.getProperty("user.dir"));

        while (true) {
            reapJobs(jobs); // Wipes dead jobs from the list instantly
            System.out.print("$ ");
            String s = sc.nextLine();

            if (s.equals("exit")) {
                break;
            }

            // ==========================================
            // CONCURRENT PIPELINE ENGINE
            // ==========================================
            if (s.contains("|")) {
                List<String> stages = splitPipeline(s);
                InputStream prevOutput = null;
                Process lastExternalProcess = null;

                for (int i = 0; i < stages.size(); i++) {
                    String stageStr = stages.get(i).trim();
                    if (stageStr.isEmpty())
                        continue;

                    String[] parts = parseCommand(stageStr);
                    if (parts.length == 0)
                        continue;

                    boolean isLast = (i == stages.size() - 1);
                    String cmd = parts[0];

                    if (isBuiltin(cmd)) {
                        String outStr = runBuiltin(parts, currentDir, jobs);
                        if (isLast) {
                            System.out.print(outStr);
                        } else {
                            prevOutput = new ByteArrayInputStream(outStr.getBytes(StandardCharsets.UTF_8));
                        }
                    } else {
                        ProcessBuilder pb = new ProcessBuilder(parts);
                        pb.directory(currentDir);
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

                        if (i == 0)
                            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
                        if (isLast)
                            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

                        Process p;
                        try {
                            p = pb.start();
                        } catch (IOException e) {
                            System.out.println(cmd + ": command not found");
                            prevOutput = new ByteArrayInputStream(new byte[0]);
                            continue;
                        }

                        if (i > 0 && prevOutput != null) {
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

                        if (!isLast) {
                            prevOutput = p.getInputStream();
                        }
                        lastExternalProcess = p;
                    }
                }

                if (lastExternalProcess != null) {
                    lastExternalProcess.waitFor();
                }
                continue;
            }

            // ==========================================
            // STANDALONE COMMANDS
            // ==========================================
            else if (s.startsWith("echo ")) {
                String[] parts = parseCommand(s);
                String outputFile = null;
                String errorFile = null;
                boolean appendOutput = false;
                boolean appendError = false;
                List<String> echoParts = new ArrayList<>();

                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].equals(">") || parts[i].equals("1>")) {
                        outputFile = parts[i + 1];
                        appendOutput = false;
                        i++;
                    } else if (parts[i].equals(">>") || parts[i].equals("1>>")) {
                        outputFile = parts[i + 1];
                        appendOutput = true;
                        i++;
                    } else if (parts[i].equals("2>")) {
                        errorFile = parts[i + 1];
                        appendError = false;
                        i++;
                    } else if (parts[i].equals("2>>")) {
                        errorFile = parts[i + 1];
                        appendError = true;
                        i++;
                    } else {
                        echoParts.add(parts[i]);
                    }
                }

                StringBuilder result = new StringBuilder();
                for (int i = 0; i < echoParts.size(); i++) {
                    if (i > 0)
                        result.append(" ");
                    result.append(echoParts.get(i));
                }

                if (errorFile != null) {
                    Files.writeString(Path.of(errorFile), "",
                            appendError ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.CREATE);
                }

                if (outputFile != null) {
                    String content = result.toString() + System.lineSeparator();
                    if (appendOutput) {
                        Files.writeString(Path.of(outputFile), content, StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    } else {
                        Files.writeString(Path.of(outputFile), content);
                    }
                } else {
                    System.out.println(result);
                }
            }

            else if (s.equals("pwd")) {
                System.out.println(currentDir.getCanonicalPath());
            }

            else if (s.startsWith("cd ")) {
                String path = s.substring(3);
                File target = path.equals("~") ? new File(System.getenv("HOME"))
                        : path.startsWith("/") ? new File(path) : new File(currentDir, path);

                if (target.exists() && target.isDirectory()) {
                    currentDir = target.getCanonicalFile();
                } else {
                    System.out.println("cd: " + path + ": No such file or directory");
                }
            }

            else if (s.equals("jobs")) {
                List<Job> completedJobs = new ArrayList<>();
                for (int i = 0; i < jobs.size(); i++) {
                    Job job = jobs.get(i);
                    char marker = (i == jobs.size() - 1) ? '+' : (i == jobs.size() - 2) ? '-' : ' ';

                    if (job.process.isAlive()) {
                        System.out.printf("[%d]%c  %-24s%s%n", job.jobNumber, marker, "Running", job.command);
                    } else {
                        String cmd = job.command.endsWith(" &") ? job.command.substring(0, job.command.length() - 2)
                                : job.command;
                        System.out.printf("[%d]%c  %-24s%s%n", job.jobNumber, marker, "Done", cmd);
                        completedJobs.add(job);
                    }
                }
                jobs.removeAll(completedJobs); // Evict announced jobs
            }

            else if (s.startsWith("type ")) {
                System.out.print(runBuiltin(parseCommand(s), currentDir, jobs));
            }

            else {
                String[] parts = parseCommand(s);
                boolean background = false;

                if (parts.length > 0 && parts[parts.length - 1].equals("&")) {
                    background = true;
                    parts = Arrays.copyOf(parts, parts.length - 1);
                }

                String cmd = parts[0];
                boolean found = false;

                for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
                    File f = new File(dir, cmd);
                    if (f.exists() && f.canExecute()) {
                        ProcessBuilder pb = new ProcessBuilder(parts);
                        pb.directory(currentDir);
                        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

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

            case "jobs":
                for (int i = 0; i < jobs.size(); i++) {
                    Job job = jobs.get(i);
                    char marker = (i == jobs.size() - 1) ? '+' : (i == jobs.size() - 2) ? '-' : ' ';
                    if (job.process.isAlive()) {
                        out.append(String.format("[%d]%c  %-24s%s%n", job.jobNumber, marker, "Running", job.command));
                    }
                }
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
                    if (!found) {
                        out.append(target).append(": not found").append(System.lineSeparator());
                    }
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
        jobs.removeAll(deadJobs); // Instant total eviction
    }
}