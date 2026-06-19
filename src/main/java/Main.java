import java.util.*;
import java.io.File;
import java.util.Scanner;

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

                for (int i = 1; i < parts.length; i++) {
                    if (i > 1)
                        System.out.print(" ");
                    System.out.print(parts[i]);
                }
                System.out.println();
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
                String cmd = parts[0];

                boolean found = false;

                for (String dir : System.getenv("PATH").split(File.pathSeparator)) {
                    File f = new File(dir, cmd);

                    if (f.exists() && f.canExecute()) {
                        ProcessBuilder pb = new ProcessBuilder(parts);
                        pb.directory(currentDir);
                        pb.inheritIO();
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

            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            } else if (Character.isWhitespace(c)
                    && !inSingleQuote
                    && !inDoubleQuote) {

                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            args.add(current.toString());
        }

        return args.toArray(new String[0]);
    }
}