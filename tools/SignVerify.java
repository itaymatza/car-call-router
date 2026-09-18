import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.*;

/** Thin launcher for AOSP apksig; it does not implement a custom signature scheme. */
public final class SignVerify {
 public static void main(String[] args) throws Exception {
  File apk;
  if (args[0].equals("sign")) {
   char[] pass = Files.readString(Path.of(args[2])).trim().toCharArray();
   KeyStore store = KeyStore.getInstance("PKCS12");
   try(InputStream in = new FileInputStream(args[1])) { store.load(in, pass); }
   PrivateKey key = (PrivateKey)store.getKey("router",pass);
   X509Certificate cert = (X509Certificate)store.getCertificate("router");
   var cfg = new ApkSigner.SignerConfig.Builder("carcallrouter", key, List.of(cert)).build();
   apk = new File(args[4]);
   new ApkSigner.Builder(List.of(cfg)).setInputApk(new File(args[3])).setOutputApk(apk)
    .setMinSdkVersion(34).setV1SigningEnabled(false).setV2SigningEnabled(true)
    .setV3SigningEnabled(true).setV4SigningEnabled(false).setAlignmentPreserved(true)
    .setDebuggableApkPermitted(false).setCreatedBy("CallRouteCompanion-AOSP-apksig").build().sign();
   Arrays.fill(pass,'\0');
  } else { apk = new File(args[1]); }
  var result = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(34).build().verify();
  System.out.println("verified=" + result.isVerified());
  System.out.println("supportedPlatform_v3=" + result.isVerifiedUsingV3Scheme());
  var v2 = new ApkVerifier.Builder(apk).setMinCheckedPlatformVersion(24).setMaxCheckedPlatformVersion(27).build().verify();
  System.out.println("v2BlockSeparatelyVerified=" + v2.isVerifiedUsingV2Scheme() + "; v2Errors=" + v2.getAllErrors());
  System.out.println("errors=" + result.getAllErrors());
  System.out.println("warnings=" + result.getWarnings());
  for (var cert : result.getSignerCertificates()) {
   System.out.println("certificateSHA256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded())));
  }
  if (!result.isVerified() || result.containsErrors() || !v2.isVerifiedUsingV2Scheme() || v2.containsErrors() || !result.isVerifiedUsingV3Scheme()) {
   throw new SecurityException("APK signature verification failed");
  }
 }
}
