import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
        Scanner sc = new Scanner(System.in);
        while(true){
        System.out.print("$ ");
        
        String s = sc.next();
        if(s.equals("exit")){
            break;
        }
        else if(s.equals("echo")){
            System.out.println(s);
        }
        else{
            System.out.println(s + ":" + " " + "command not found");
        }
        }
    }
}
