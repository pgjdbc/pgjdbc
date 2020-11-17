import java.net.ServerSocket
import java.nio.channels.FileChannel;

public class Util {
    public static int findPort() {
        int port
        ServerSocket s = new ServerSocket(0)
        port = s.getLocalPort()
        s.close()
        return port
    }
    public static void appendToFile(String fileName, String text, truncate=false) {

        new File(fileName).with() { f ->
            if ( truncate ) {
                FileChannel outChannel = new FileOutputStream(f, true).getChannel()
                outChannel.truncate(0)
                outChannel.close()
            }
            f.append("$text\n ")
        }
    }
    public static String readFile(String fileName) {
        new FileInputStream(fileName).text
    }
}
