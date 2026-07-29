import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

public class BouncyCastleFeature implements Feature {
    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        RuntimeClassInitialization.initializeAtBuildTime("org.bouncycastle");
        BouncyCastleProvider bc = new BouncyCastleProvider();
        Security.addProvider(bc);
        Security.addProvider(new BouncyCastleJsseProvider(bc));
    }
}
