package cn.focusledger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.security.KeyPairGenerator;
import java.security.spec.MGF1ParameterSpec;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

final class SyncIdentity {
    static final String ORIGIN="https://shiben-ai-personal.gof2088596.chatgpt.site";
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("shiben-ai",Context.MODE_PRIVATE);}
    static String status(Context c){return prefs(c).getString("status","尚未连接");}
    static void status(Context c,String s){prefs(c).edit().putString("status",s).apply();}
    static boolean connected(Context c){return prefs(c).contains("encrypted")&&!prefs(c).getBoolean("disabled",false);}
    private static KeyStore keys() throws Exception {KeyStore k=KeyStore.getInstance("AndroidKeyStore");k.load(null);return k;}
    private static SecretKey aes() throws Exception {
        KeyStore k=keys();if(!k.containsAlias("shiben-ai-storage")){
            KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder("shiben-ai-storage",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();
        }return (SecretKey)keys().getKey("shiben-ai-storage",null);
    }
    static String begin(Context c) throws Exception {
        if(prefs(c).contains("encrypted"))throw new IllegalStateException("请先断开现有连接");
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);java.security.KeyPair pair=g.generateKeyPair();
        Cipher protect=Cipher.getInstance("AES/GCM/NoPadding");protect.init(Cipher.ENCRYPT_MODE,aes());
        String encryptedKey=Base64.encodeToString(protect.doFinal(pair.getPrivate().getEncoded()),Base64.NO_WRAP);
        String nonce=java.util.UUID.randomUUID().toString();
        if(!prefs(c).edit().putString("nonce",nonce).putLong("pairStarted",System.currentTimeMillis()).putString("pairKey",encryptedKey).putString("pairIv",Base64.encodeToString(protect.getIV(),Base64.NO_WRAP)).commit())throw new IllegalStateException("无法保存配对状态");
        String pub=Base64.encodeToString(pair.getPublic().getEncoded(),Base64.NO_WRAP);
        return ORIGIN+"/pair?nonce="+nonce+"&key="+java.net.URLEncoder.encode(pub,"UTF-8");
    }
    static void accept(Context c,String payload) throws Exception {
        if(payload==null||payload.length()>16000)throw new IllegalArgumentException("连接信息无效");
        long age=System.currentTimeMillis()-prefs(c).getLong("pairStarted",0);if(age<0||age>600000||connected(c))throw new IllegalArgumentException("配对已过期，请重新连接");
        JSONObject envelope=new JSONObject(new String(Base64.decode(payload,Base64.DEFAULT),"UTF-8"));
        Cipher unprotect=Cipher.getInstance("AES/GCM/NoPadding");unprotect.init(Cipher.DECRYPT_MODE,aes(),new GCMParameterSpec(128,Base64.decode(prefs(c).getString("pairIv",""),Base64.DEFAULT)));
        java.security.PrivateKey privateKey=java.security.KeyFactory.getInstance("RSA").generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(unprotect.doFinal(Base64.decode(prefs(c).getString("pairKey",""),Base64.DEFAULT))));
        Cipher rsa=Cipher.getInstance("RSA/ECB/OAEPPadding");rsa.init(Cipher.DECRYPT_MODE,privateKey,new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,PSource.PSpecified.DEFAULT));
        byte[] secret=rsa.doFinal(Base64.decode(envelope.getString("key"),Base64.DEFAULT));
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.DEFAULT)));
        byte[] data=cipher.doFinal(Base64.decode(envelope.getString("data"),Base64.DEFAULT));JSONObject credentials=new JSONObject(new String(data,"UTF-8"));
        if(!ORIGIN.equals(credentials.getString("url"))||!prefs(c).getString("nonce","").equals(credentials.getString("nonce")))throw new IllegalArgumentException("连接来源不匹配");
        credentials.getString("token");credentials.getString("gate");credentials.getString("deviceId");
        cipher.init(Cipher.ENCRYPT_MODE,aes());byte[] enc=cipher.doFinal(data);
        if(!prefs(c).edit().putString("encrypted",Base64.encodeToString(enc,Base64.NO_WRAP)).putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).remove("disabled").remove("nonce").remove("pairStarted").remove("pairKey").remove("pairIv").putString("status","已连接，等待首次同步").commit())throw new IllegalStateException("保存连接失败");

    }
    static JSONObject credentials(Context c) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,aes(),new GCMParameterSpec(128,Base64.decode(prefs(c).getString("iv",""),Base64.DEFAULT)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(prefs(c).getString("encrypted",""),Base64.DEFAULT)),"UTF-8"));
    }
    static void disable(Context c){prefs(c).edit().putBoolean("disabled",true).putString("status","已停止同步，正在撤销授权…").commit();}
    static void clear(Context c){prefs(c).edit().clear().putString("status","已断开，本机记录保留").commit();}
}
