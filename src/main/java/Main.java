import java.util.*;
import java.io.File;

public class Main {
    public static void main(String[] args) throws Exception {
        Scanner sc = new Scanner(System.in);

        File currentDir = new File(System.getProperty("user.dir"));

        while (true) {
            System.out.print("$ ");

            String s = sc.nextLine();

            if (s.equals("exit")) {
                break;
            }

            else if (s.startsWith("echo ")) {
                String[] parts = parseCommand(s);

                String outputFile = null;
                String errorFile = null;
                boolean appendOutput = false;
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
                    java.nio.file.Files.writeString(
                            java.nio.file.Path.of(errorFile),
                            "");
                }

                if (outputFile != null) {

                    if (appendOutput) {
                        java.nio.file.Files.writeString(
                                java.nio.file.Path.of(outputFile),
                                result.toString() + System.lineSeparator(),
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.APPEND);
                    } else {
                        java.nio.file.Files.writeString(
                                java.nio.file.Path.of(outputFile),
                                result.toString() + System.lineSeparator());
                    }

                }
            }

            else if (s.equals("pwd")) {
                System.out.println(currentDir.getCanonicalPath());
            }

            else if (s.startsWith("cd ")) {
                String path = s.substring(3);

                File target;

                if (path.equals("~")) {
                    target = new File(System.getenv("HOME"));
                } else if (path.startsWith("/")) {
                    target = new File(path);
                } else {
                    target = new File(currentDir, path);
                }

                if (target.exists() && target.isDirectory()) {
                    currentDir = target.getCanonicalFile();
                } else {
                    System.out.println(
                            "cd: " + path + ": No such file or directory");
                }
            }

            else if (s.startsWith("type ")) {
                String command = s.substring(5);

                if (command.equals("echo")
                        || command.equals("type")
                        || command.equals("exit")
                        || command.equals("pwd")
                        || command.equals("cd")) {
                    System.out.println(command + " is a shell builtin");
                } else {
                    boolean found = false;

                    for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
                        File f = new File(dir, command);

                        if (f.exists() && f.canExecute()) {
                            System.out.println(command + " is " + f.getAbsolutePath());
                            found = true;
                            break;
                        }
                    }

                    if (!found) {
                        System.out.println(command + ": not found");
                    }
                }
            }

            else {
                String[] parts = parseCommand(s);

                String outputFile = null;
                String errorFile = null;
                boolean appendOutput = false;

                List<String> cmdParts = new ArrayList<>();

                for (int i = 0; i < parts.length; i++) {
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
                        i++;
                    } else {
                        cmdParts.add(parts[i]);
                    }
                }

                parts = cmdParts.toArray(new String[0]);

                String cmd = parts[0];

                boolean found = false;

                for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
                    File f = new File(dir, cmd);

                    if (f.exists() && f.canExecute()) {
                        ProcessBuilder pb = new ProcessBuilder(parts);
                        pb.directory(currentDir);

                        if (outputFile != null) {

                            if (appendOutput) {
                                pb.redirectOutput(
                                        ProcessBuilder.Redirect.appendTo(
                                                new File(outputFile)));
                            } else {
                                pb.redirectOutput(new File(outputFile));
                            }

                        }

                        if (errorFile != null) {
                            pb.redirectError(new File(errorFile));
                        } else {
                            pb.redirectError(ProcessBuilder.Redirect.INHERIT);
                        }

                        pb.start().waitFor();

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

    private static String[] parseCommand(String input) {
        java.util.List<String> args = new java.util.ArrayList<>();

        StringBuilder current = new StringBuilder();

        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // Outside quotes: backslash escapes ANY character
            if (c == '\\' && !inSingleQuote && !inDoubleQuote) {
                if (i + 1 < input.length()) {
                    current.append(input.charAt(i + 1));
                    i++;
                }
            }

            // Inside double quotes: only \" and \\ are special
            else if (c == '\\' && inDoubleQuote) {
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
            }

            else if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            }

            else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            else if (Character.isWhitespace(c)
                    && !inSingleQuote
                    && !inDoubleQuote) {

                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            }

            else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args.toArray(new String[0]);
    }
}