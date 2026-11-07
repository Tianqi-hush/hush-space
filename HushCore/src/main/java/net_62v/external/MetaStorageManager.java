package net_62v.external;

import android.os.Environment;
import java.io.File;
import com.hush.utils.FileUtils;
import org.lsposed.lsparanoid.Obfuscate;

@Obfuscate
public class MetaStorageManager {

    /**
     * Required by ApkEnv.java which calls obtainAppExternalStorageDir(packageName)
     */
    public static String obtainAppExternalStorageDir(String packageName) {
        File containerPath = Environment.getExternalStorageDirectory();
        FileUtils.mkdirs(containerPath);
        return containerPath.getAbsolutePath();
    }

    /**
     * Base method to get the external storage directory.
     */
    public static File obtainAppExternalStorageDir() {
        File containerPath = Environment.getExternalStorageDirectory();
        FileUtils.mkdirs(containerPath);
        return containerPath;
    }
    
    /**
     * Gets the virtual OBB container path.
     */
    public static File getObbContainerPath(String packageName) {
        try {
            return new File(obtainAppExternalStorageDir() + "/Android/obb", packageName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Gets the virtual Data container path.
     */
    public static File getDataContainerPath(String packageName) {
        try {
            return new File(obtainAppExternalStorageDir() + "/Android/data", packageName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
