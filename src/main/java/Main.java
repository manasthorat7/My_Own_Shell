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
                System.out.println(s.substring(5));
            }

            else if (s.equals("pwd")) {
                System.out.println(currentDir.getCanonicalPath());
            }

            else if (s.startsWith("cd ")) {
                String path = s.substring(3);

                File target;

                if (path.startsWith("/")) {
                    // absolute path
                    target = new File(path);
                } else {
                    // relative path
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
                String[] parts = s.split(" ");
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
}