import java.io.File;
import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
        Scanner sc = new Scanner(System.in);
        while(true){
        System.out.print("$ ");
        
        String s = sc.nextLine();
        if(s.equals("exit")){
            break;
        }
        else if(s.startsWith("echo")){
            System.out.println(s.substring(5));
        }
        else if(s.startsWith("type")){
            String command = s.substring(5);
            if(command.equals("echo") || command.equals("type") || command.equals("exit")){
                System.out.println(command + " is a shell builtin");
            }
            else{
                    
                String[] parts = s.split(" ");
                String cmd = parts[0];

                boolean found = false;

                    for(String dir : System.getenv("PATH").split(File.pathSeparator)){
                        File f = new File(dir, cmd);

                         if(f.exists() && f.canExecute()){
                            parts[0] = f.getAbsolutePath();

                            ProcessBuilder pb = new ProcessBuilder(parts);
                            pb.inheritIO();
                            pb.start().waitFor();
                            found = true;
                            break;
                        }
                   }
                    if(!found){
                     System.out.println(cmd + ": not found");
                    }
               }
            }
        else{
            System.out.println(s + ":" + " " + "command not found");
        }
        }
    }
}
