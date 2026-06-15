import java.util.Scanner;

public class Main {
    public static void main(String[] args) throws Exception {
        // TODO: Uncomment the code below to pass the first stage
        while(true){
        System.out.print("$ ");
        Scanner sc = new Scanner(System.in);
        String s = sc.next();
        System.out.println(s + ":" + " " + "command not found");
        sc.close();
        }
    }
}
