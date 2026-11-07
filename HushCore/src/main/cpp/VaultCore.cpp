// VaultCore.cpp — Hash-Token Activation Guard
// Gate 1: Java (8 checks) → Gate 2: Native env (4 checks) →
// Gate 3: HMAC-signed POST (URL hidden) → Gate 4: 128-bit token mint →
// Validator: reconstruct + compare hash, no boolean stored anywhere
#include "VaultCore.h"
#include "VaultConfig.h"
#include "Log.h"
#include <jni.h>
#include <string>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <errno.h>
#include <time.h>
#include <android/log.h>
#include <dirent.h>
#include <sys/types.h>


// ── Compile-time XOR encoded URL ─────────────────────────────────────────
// Edit VAULT_URL in VaultConfig.h — no byte arrays to maintain.
// Compiler XOR-encodes at build time; binary has zero plaintext string.
static constexpr auto kUrlEncoded = VAULT_ENC_STR(VAULT_URL);

static std::string assembleUrl() {
    std::string s;
    s.reserve(kUrlEncoded.len);
    for (size_t i = 0; i < kUrlEncoded.len; i++)
        s += (char)(kUrlEncoded.data[i] ^ VAULT_XOR_KEY);
    return s;
}


// ── HMAC key (32 B) ^ 0x7F ────────────────────────────────────────────────
static const uint8_t kH[]={0x32,0x1A,0x13,0x1B,0x10,0x18,0x3B,0x1B,
                            0x27,0x1A,0x18,0x13,0x3B,0x14,0x1C,0x1B,
                            0x4C,0x54,0x56,0x4B,0x13,0x18,0x57,0x1A,
                            0x50,0x57,0x5A,0x1B,0x4F,0x57,0x07,0x4F};
static const uint8_t kHk=0x7F;

// ── Vault XOR key (16 B) ^ 0x3C ──────────────────────────────────────────
static const uint8_t kV[]={0x51,0x29,0x6F,0x1A,0x78,0x0C,0x45,0x33,
                            0x6B,0x11,0x2D,0x7F,0x59,0x44,0x08,0x77};
static const uint8_t kVk=0x3C;

// ── SHA-256 (self-contained) ──────────────────────────────────────────────
#define R(x,n)(((x)>>(n))|((x)<<(32-(n))))
#define CH(x,y,z)(((x)&(y))^(~(x)&(z)))
#define MJ(x,y,z)(((x)&(y))^((x)&(z))^((y)&(z)))
#define E0(x)(R(x,2)^R(x,13)^R(x,22))
#define E1(x)(R(x,6)^R(x,11)^R(x,25))
#define S0(x)(R(x,7)^R(x,18)^((x)>>3))
#define S1(x)(R(x,17)^R(x,19)^((x)>>10))
static const uint32_t KK[]={
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2};
typedef struct{uint32_t h[8];uint8_t b[64];uint32_t n,total;}SX;
static void sxt(uint32_t*h,const uint8_t*d){
    uint32_t a,b,c,dd,e,f,g,hh,t1,t2,m[64];
    for(int i=0,j=0;i<16;i++,j+=4)
        m[i]=((uint32_t)d[j]<<24)|((uint32_t)d[j+1]<<16)|((uint32_t)d[j+2]<<8)|d[j+3];
    for(int i=16;i<64;i++)m[i]=S1(m[i-2])+m[i-7]+S0(m[i-15])+m[i-16];
    a=h[0];b=h[1];c=h[2];dd=h[3];e=h[4];f=h[5];g=h[6];hh=h[7];
    for(int i=0;i<64;i++){t1=hh+E1(e)+CH(e,f,g)+KK[i]+m[i];t2=E0(a)+MJ(a,b,c);
        hh=g;g=f;f=e;e=dd+t1;dd=c;c=b;b=a;a=t1+t2;}
    h[0]+=a;h[1]+=b;h[2]+=c;h[3]+=dd;h[4]+=e;h[5]+=f;h[6]+=g;h[7]+=hh;
}
static void sxi(SX*c){
    c->h[0]=0x6a09e667;c->h[1]=0xbb67ae85;c->h[2]=0x3c6ef372;c->h[3]=0xa54ff53a;
    c->h[4]=0x510e527f;c->h[5]=0x9b05688c;c->h[6]=0x1f83d9ab;c->h[7]=0x5be0cd19;
    c->n=0;c->total=0;
}
static void sxu(SX*c,const uint8_t*in,size_t l){
    for(size_t i=0;i<l;i++){c->b[c->n++]=in[i];c->total++;
        if(c->n==64){sxt(c->h,c->b);c->n=0;}}
}
static void sxf(SX*c,uint8_t*out){
    uint32_t t=c->total;
    c->b[c->n++]=0x80;
    if(c->n>56){while(c->n<64)c->b[c->n++]=0;sxt(c->h,c->b);c->n=0;}
    while(c->n<56)c->b[c->n++]=0;
    uint64_t bits=(uint64_t)t*8;
    for(int i=0;i<8;i++)c->b[56+i]=0;
    c->b[63]=(bits)&0xFF;c->b[62]=(bits>>8)&0xFF;
    c->b[61]=(bits>>16)&0xFF;c->b[60]=(bits>>24)&0xFF;
    sxt(c->h,c->b);
    for(int i=0;i<8;i++){out[i*4]=(c->h[i]>>24)&0xFF;out[i*4+1]=(c->h[i]>>16)&0xFF;
        out[i*4+2]=(c->h[i]>>8)&0xFF;out[i*4+3]=c->h[i]&0xFF;}
}
static void sha256(const uint8_t*msg,size_t l,uint8_t*out){SX c;sxi(&c);sxu(&c,msg,l);sxf(&c,out);}
static void hmac256(const uint8_t*key,size_t kl,const uint8_t*msg,size_t ml,uint8_t*out){
    uint8_t k[64]={};
    if(kl>64){sha256(key,kl,k);}else memcpy(k,key,kl);
    uint8_t ip[64],op[64];
    for(int i=0;i<64;i++){ip[i]=k[i]^0x36;op[i]=k[i]^0x5C;}
    SX c;uint8_t inner[32];
    sxi(&c);sxu(&c,ip,64);sxu(&c,msg,ml);sxf(&c,inner);
    sxi(&c);sxu(&c,op,64);sxu(&c,inner,32);sxf(&c,out);
}
static void toHex(const uint8_t*in,size_t l,char*out){
    static const char*hx="0123456789abcdef";
    for(size_t i=0;i<l;i++){out[i*2]=hx[(in[i]>>4)&0xF];out[i*2+1]=hx[in[i]&0xF];}
    out[l*2]=0;
}
static bool fromHex(const char*s,uint8_t*out,size_t len){
    for(size_t i=0;i<len;i++){
        char hi=s[i*2],lo=s[i*2+1];
        auto v=[](char c)->int{return c>='0'&&c<='9'?c-'0':c>='a'&&c<='f'?c-'a'+10:c>='A'&&c<='F'?c-'A'+10:-1;};
        int h=v(hi),l2=v(lo);if(h<0||l2<0)return false;
        out[i]=(uint8_t)((h<<4)|l2);
    }
    return true;
}

// ── Vault XOR encrypt/decrypt (key is device-derived) ────────────────────
static void vaultXor(const uint8_t*key16,const uint8_t*in,uint8_t*out,size_t len){
    for(size_t i=0;i<len;i++)out[i]=in[i]^key16[i%16]^(uint8_t)(i*0x37+0x5B);
}
static void vaultKey(const char*deviceId,uint8_t*key16){
    // device-derived 16-byte key = SHA-256(deviceId || fixed_salt)[0:16]
    uint8_t salt[16];for(int i=0;i<16;i++)salt[i]=kV[i]^kVk;
    size_t dl=strlen(deviceId);
    uint8_t buf[256]={};size_t bl=0;
    memcpy(buf,deviceId,dl<128?dl:128);bl+=dl<128?dl:128;
    memcpy(buf+bl,salt,16);bl+=16;
    uint8_t h[32];sha256(buf,bl,h);
    memcpy(key16,h,16);
}

// ── JSON field extractor — handles both compact and pretty-printed JSON ──────
// Matches: "key":"value"  OR  "key": "value"  (space after colon is optional)
static bool jsonStr(const char*json,const char*key,char*out,size_t outLen){
    // Build needle without trailing quote so we can skip optional whitespace
    char needle[64]={};snprintf(needle,sizeof(needle),"\"%s\":",key);
    const char*p=strstr(json,needle);if(!p)return false;
    p+=strlen(needle);
    // skip optional whitespace between : and opening quote
    while(*p==' '||*p=='\t'||*p=='\r'||*p=='\n') p++;
    if(*p!='"') return false; // not a string value
    p++; // skip opening quote
    size_t i=0;
    while(p[i]&&p[i]!='"'&&i<outLen-1){out[i]=p[i];i++;}
    out[i]=0;
    return true; // return true even for empty strings — caller decides default
}

// ── Status check — handles compact and pretty-printed JSON ───────────────────
static bool jsonStatusSuccess(const char*json){
    char status[32]={};
    if(!jsonStr(json,"status",status,sizeof(status))) return false;
    return strcmp(status,"success")==0;
}

// ── 128-bit token mint ────────────────────────────────────────────────────
// token128 = SHA-256(deviceId || ":" || serverSeed || ":" || expiry)[0:16]
static void mintToken128(const char*devId,const char*seed,const char*expiry,uint8_t*tok16){
    char buf[512]={};
    snprintf(buf,sizeof(buf),"%s:%s:%s",devId,seed,expiry);
    uint8_t h[32];sha256((const uint8_t*)buf,strlen(buf),h);
    memcpy(tok16,h,16);
}

// Vault plaintext format:
// <hex(token128)>|<expiry>|<seed>|<devId>|<sha256_checksum[0:4] hex>
// = 32 + 1 + 20 + 1 + 64 + 1 + 32 + 1 + 8 = ~160 bytes max
static void buildVaultPlain(const char*tok16hex,const char*expiry,
                             const char*seed,const char*devId,const char*userKey,char*out,size_t outLen){
    // compute inner checksum: SHA-256(tok16hex||expiry||seed||userKey)[0:4]
    char tmp[512]={};snprintf(tmp,sizeof(tmp),"%s%s%s%s",tok16hex,expiry,seed,userKey);
    uint8_t chk[32];sha256((const uint8_t*)tmp,strlen(tmp),chk);
    char chkHex[9];toHex(chk,4,chkHex);
    snprintf(out,outLen,"%s|%s|%s|%s|%s|%s",tok16hex,expiry,seed,devId,userKey,chkHex);
}

// ── JNI class / transport refs ────────────────────────────────────────────
static jclass    gCls=nullptr;
static jmethodID gTransport=nullptr;  // _t(String,String,String):String
static jmethodID gWrite=nullptr;      // _w(Context,[B):void
static jmethodID gRead=nullptr;       // _r(Context):[B

// ── GATE 2 — native integrity ─────────────────────────────────────────────
static bool chkTracerPid(){
    char buf[512]={};int fd=open("/proc/self/status",O_RDONLY);
    if(fd<0)return false;read(fd,buf,sizeof(buf)-1);close(fd);
    const char*p=strstr(buf,"TracerPid:");
    return p&&atoi(p+10)==0;
}
static bool chkFridaMaps(){
    char line[512];FILE*f=fopen("/proc/self/maps","r");if(!f)return true;
    bool ok=true;
    while(fgets(line,sizeof(line),f))
        if(strstr(line,"frida")||strstr(line,"gum-js")){ok=false;break;}
    fclose(f);return ok;
}
static bool chkFridaPort(){
    char b[2048]={};int fd=open("/proc/net/tcp6",O_RDONLY);
    if(fd<0)fd=open("/proc/net/tcp",O_RDONLY);
    if(fd<0)return true;read(fd,b,sizeof(b)-1);close(fd);
    return !strstr(b,"69A2");
}
static bool chkFridaThreads(){
    DIR* d = opendir("/proc/self/task");
    if (!d) return true;
    struct dirent* entry;
    bool detected = false;
    while ((entry = readdir(d)) != nullptr) {
        if (entry->d_name[0] == '.') continue;
        char path[128];
        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", entry->d_name);
        int fd = open(path, O_RDONLY);
        if (fd >= 0) {
            char name[64] = {};
            read(fd, name, sizeof(name) - 1);
            close(fd);
            if (strstr(name, "frida") || strstr(name, "gum-js") || strstr(name, "gmain") || strstr(name, "pool-frida")) {
                detected = true;
                break;
            }
        }
    }
    closedir(d);
    return !detected;
}

int VaultCore_gate2(JNIEnv*,void*){
    int fail=0;
    if(!chkTracerPid())fail|=1;
    // ptrace(PTRACE_TRACEME) is blocked by seccomp on Android 10+ — use wchan instead
    { char w[64]={};int fd=open("/proc/self/wchan",O_RDONLY);
      if(fd>=0){read(fd,w,sizeof(w)-1);close(fd);}
      if(strstr(w,"ptrace_stop")||strstr(w,"trace"))fail|=2; }
    if(!chkFridaMaps())fail|=4;
    if(!chkFridaPort())fail|=8;
    if(!chkFridaThreads())fail|=16;
    return fail;
}

static std::string getAppName(JNIEnv* env, jobject ctx) {
    if (!ctx) return "";
    jclass contextClass = env->GetObjectClass(ctx);
    if (!contextClass) return "";
    jmethodID getPackageManagerMethod = env->GetMethodID(contextClass, "getPackageManager", "()Landroid/content/pm/PackageManager;");
    if (!getPackageManagerMethod) return "";
    jobject packageManager = env->CallObjectMethod(ctx, getPackageManagerMethod);
    if (!packageManager) return "";

    jmethodID getApplicationInfoMethod = env->GetMethodID(contextClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;");
    if (!getApplicationInfoMethod) {
        env->DeleteLocalRef(packageManager);
        return "";
    }
    jobject applicationInfo = env->CallObjectMethod(ctx, getApplicationInfoMethod);
    if (!applicationInfo) {
        env->DeleteLocalRef(packageManager);
        return "";
    }

    jclass packageManagerClass = env->GetObjectClass(packageManager);
    if (!packageManagerClass) {
        env->DeleteLocalRef(packageManager);
        env->DeleteLocalRef(applicationInfo);
        return "";
    }
    jmethodID getApplicationLabelMethod = env->GetMethodID(packageManagerClass, "getApplicationLabel", "(Landroid/content/pm/ApplicationInfo;)Ljava/lang/CharSequence;");
    if (!getApplicationLabelMethod) {
        env->DeleteLocalRef(packageManager);
        env->DeleteLocalRef(applicationInfo);
        env->DeleteLocalRef(packageManagerClass);
        return "";
    }
    jobject charSequenceLabel = env->CallObjectMethod(packageManager, getApplicationLabelMethod, applicationInfo);
    if (!charSequenceLabel) {
        env->DeleteLocalRef(packageManager);
        env->DeleteLocalRef(applicationInfo);
        env->DeleteLocalRef(packageManagerClass);
        return "";
    }

    jclass charSequenceClass = env->GetObjectClass(charSequenceLabel);
    if (!charSequenceClass) {
        env->DeleteLocalRef(packageManager);
        env->DeleteLocalRef(applicationInfo);
        env->DeleteLocalRef(packageManagerClass);
        env->DeleteLocalRef(charSequenceLabel);
        return "";
    }
    jmethodID toStringMethod = env->GetMethodID(charSequenceClass, "toString", "()Ljava/lang/String;");
    if (!toStringMethod) {
        env->DeleteLocalRef(packageManager);
        env->DeleteLocalRef(applicationInfo);
        env->DeleteLocalRef(packageManagerClass);
        env->DeleteLocalRef(charSequenceLabel);
        env->DeleteLocalRef(charSequenceClass);
        return "";
    }
    jstring jAppName = (jstring)env->CallObjectMethod(charSequenceLabel, toStringMethod);
    if (!jAppName) {
        env->DeleteLocalRef(packageManager);
        env->DeleteLocalRef(applicationInfo);
        env->DeleteLocalRef(packageManagerClass);
        env->DeleteLocalRef(charSequenceLabel);
        env->DeleteLocalRef(charSequenceClass);
        return "";
    }

    const char* appNameChars = env->GetStringUTFChars(jAppName, nullptr);
    std::string appNameStr = appNameChars ? appNameChars : "";
    if (appNameChars) {
        env->ReleaseStringUTFChars(jAppName, appNameChars);
    }
    env->DeleteLocalRef(packageManager);
    env->DeleteLocalRef(applicationInfo);
    env->DeleteLocalRef(packageManagerClass);
    env->DeleteLocalRef(charSequenceLabel);
    env->DeleteLocalRef(charSequenceClass);
    env->DeleteLocalRef(jAppName);
    return appNameStr;
}

static std::string urlEncode(const std::string& value) {
    std::string result;
    result.reserve(value.size());
    for (char c : value) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
            c == '-' || c == '_' || c == '.' || c == '~') {
            result += c;
        } else if (c == ' ') {
            result += "%20";
        } else {
            char hex[4];
            snprintf(hex, sizeof(hex), "%%%02X", (unsigned char)c);
            result += hex;
        }
    }
    return result;
}

// ── GATE 3 — hidden-URL network call ─────────────────────────────────────
int VaultCore_gate3(JNIEnv*env,jobject ctx,
                    const char*userKey,const char*pkg,const char*devId,
                    char*outJson,size_t outLen){
    if(!gCls||!gTransport)return -1;
    std::string appName = getAppName(env, ctx);
    if (appName.empty()) {
        appName = pkg;
    }
    std::string encodedAppName = urlEncode(appName);

    char body[1024]={};
    // Include real app_name — server requires it or returns missing-params error
    snprintf(body,sizeof(body),
             "user_key=%s&package_name=%s&device_id=%s&app_name=%s",
             userKey,pkg,devId,encodedAppName.c_str());
    uint8_t hkey[32];for(int i=0;i<32;i++)hkey[i]=kH[i]^kHk;
    uint8_t mac[32];hmac256(hkey,32,(const uint8_t*)body,strlen(body),mac);
    char macHex[65];toHex(mac,32,macHex);

    std::string url = assembleUrl();
    jstring jU=env->NewStringUTF(url.c_str());
    jstring jB=env->NewStringUTF(body);
    jstring jM=env->NewStringUTF(macHex);
    memset(&url[0],0,url.size()); // wipe

    jstring resp=(jstring)env->CallStaticObjectMethod(gCls,gTransport,jU,jB,jM);
    env->DeleteLocalRef(jU);env->DeleteLocalRef(jB);env->DeleteLocalRef(jM);
    if(!resp)return -1;
    const char*rs=env->GetStringUTFChars(resp,nullptr);
    if(rs){strncpy(outJson,rs,outLen-1);env->ReleaseStringUTFChars(resp,rs);}
    env->DeleteLocalRef(resp);
    return 200;
}

// ── GATE 4 — 128-bit token mint + vault write ─────────────────────────────
// Handles real server response format:
//   { "status":"success", "expiry":"" or "YYYY-MM-DD HH:MM:SS" }
// No "seed" field needed — generated locally from devId + time.
// Returns: 1=success, 0=fail
int VaultCore_gate4(JNIEnv*env,jobject ctx,const char*json,const char*devId,const char*userKey){
    if(!json||strlen(json)<10)return 0;
    // Use helper that handles both compact and pretty-printed JSON
    if(!jsonStatusSuccess(json))return 0;

    // Extract expiry — may be empty string, in which case use far-future date
    char expiry[32]="2099-12-31 23:59:59"; // default: no expiry
    char expBuf[32]={};
    if(jsonStr(json,"expiry",expBuf,sizeof(expBuf)) && strlen(expBuf)>=10){
        // Server sent a real expiry (e.g. "2025-12-31 23:59:59")
        strncpy(expiry,expBuf,sizeof(expiry)-1);
    }
    // else: keep the far-future fallback (key has no duration set on server)

    // Generate seed locally: SHA-256(devId || expiry)[0:16] as hex
    // No "seed" field needed from server — vault is still device-bound via devId.
    char seedBuf[512]={};
    snprintf(seedBuf,sizeof(seedBuf),"%s|%s|vault_seed",devId,expiry);
    uint8_t seedHash[32];sha256((const uint8_t*)seedBuf,strlen(seedBuf),seedHash);
    char seed[65];toHex(seedHash,32,seed); // 64-char hex seed

    // Mint 128-bit token
    uint8_t tok16[16];
    mintToken128(devId,seed,expiry,tok16);
    char tok16Hex[33];toHex(tok16,16,tok16Hex);

    // Build vault plaintext
    char plain[512]={};
    buildVaultPlain(tok16Hex,expiry,seed,devId,userKey,plain,sizeof(plain));

    // Encrypt vault with device-derived key
    uint8_t dkey[16];vaultKey(devId,dkey);
    size_t pl=strlen(plain);
    uint8_t enc[512]={};
    vaultXor(dkey,(const uint8_t*)plain,enc,pl);

    // Write via Java _w()
    if(gWrite&&gCls){
        jbyteArray ja=env->NewByteArray((jsize)pl);
        env->SetByteArrayRegion(ja,0,(jsize)pl,(const jbyte*)enc);
        env->CallStaticVoidMethod(gCls,gWrite,ctx,ja);
        env->DeleteLocalRef(ja);
    }
    return 1;
}

// ── VALIDATOR — reconstruct + compare 128-bit token ──────────────────────
// Returns: 1=valid, 0=invalid, 2=expired, -1=tampered, -2=no vault
int VaultCore_queryState(JNIEnv*env,jobject ctx,const char*devId){
    if(!gRead||!gCls)return -2;
    jbyteArray encArr=(jbyteArray)env->CallStaticObjectMethod(gCls,gRead,ctx);
    if(!encArr)return -2;
    jsize len=env->GetArrayLength(encArr);
    if(len<10||len>511){env->DeleteLocalRef(encArr);return -2;}

    uint8_t enc[512]={};
    env->GetByteArrayRegion(encArr,0,len,(jbyte*)enc);
    env->DeleteLocalRef(encArr);

    // Decrypt
    uint8_t dkey[16];vaultKey(devId,dkey);
    uint8_t plain[512]={};
    vaultXor(dkey,enc,plain,(size_t)len);
    plain[len]=0;

    // Parse: tok16hex|expiry|seed|did|userKey|chkHex
    char tok16Hex[33]={},expiry[32]={},seed[128]={},did[64]={},userKey[128]={},chkHex[9]={};
    char*s=(char*)plain;
    auto nxt=[&](char*dst,size_t dsz)->bool{
        char*p=strchr(s,'|');if(!p){strncpy(dst,s,dsz-1);s+=strlen(s);return true;}
        size_t l=(size_t)(p-s);if(l>=dsz)return false;
        memcpy(dst,s,l);dst[l]=0;s=p+1;return true;
    };
    if(!nxt(tok16Hex,33)||!nxt(expiry,32)||!nxt(seed,128)||!nxt(did,64)||!nxt(userKey,128)||!nxt(chkHex,9))
        return -1;

    // Check 1: device_id matches
    if(strncmp(did,devId,strlen(devId))!=0)return -1;

    // Check 2: inner checksum
    char tmp[384]={};snprintf(tmp,sizeof(tmp),"%s%s%s%s",tok16Hex,expiry,seed,userKey);
    uint8_t chk[32];sha256((const uint8_t*)tmp,strlen(tmp),chk);
    char expectChk[9];toHex(chk,4,expectChk);
    if(strncmp(expectChk,chkHex,8)!=0)return -1;

    // Check 3: reconstruct 128-bit token and compare
    uint8_t tok16[16];mintToken128(devId,seed,expiry,tok16);
    char expectTok[33];toHex(tok16,16,expectTok);
    if(strncmp(expectTok,tok16Hex,32)!=0)return -1;

    // Check 4: expiry (format yyyy-MM-dd HH:mm:ss)
    struct tm tm={};
    if(sscanf(expiry,"%d-%d-%d %d:%d:%d",
              &tm.tm_year,&tm.tm_mon,&tm.tm_mday,
              &tm.tm_hour,&tm.tm_min,&tm.tm_sec)==6){
        tm.tm_year-=1900;tm.tm_mon-=1;
        time_t exp=mktime(&tm);
        if(exp>0&&time(nullptr)>exp)return 2; // expired
    }
    return 1; // valid
}

// ── JNI methods (opaque names a0..a4) ────────────────────────────────────
static jint  n_gate2(JNIEnv*e,jclass){return VaultCore_gate2(e,nullptr);}
static jint  n_gate3(JNIEnv*e,jclass,jobject c,jstring k,jstring p,jstring d,jobjectArray o){
    const char*key=e->GetStringUTFChars(k,nullptr);
    const char*pkg=e->GetStringUTFChars(p,nullptr);
    const char*dev=e->GetStringUTFChars(d,nullptr);
    char buf[8192]={};
    int r=VaultCore_gate3(e,c,key,pkg,dev,buf,sizeof(buf));
    e->ReleaseStringUTFChars(k,key);e->ReleaseStringUTFChars(p,pkg);e->ReleaseStringUTFChars(d,dev);
    if(r>0&&o)e->SetObjectArrayElement(o,0,e->NewStringUTF(buf));
    return r;
}
static jint  n_gate4(JNIEnv*e,jclass,jobject c,jstring j,jstring d,jstring k){
    const char*js=e->GetStringUTFChars(j,nullptr);
    const char*dv=e->GetStringUTFChars(d,nullptr);
    const char*ky=e->GetStringUTFChars(k,nullptr);
    int r=VaultCore_gate4(e,c,js,dv,ky);
    e->ReleaseStringUTFChars(j,js);e->ReleaseStringUTFChars(d,dv);e->ReleaseStringUTFChars(k,ky);
    return r;
}

jint VaultCore_liveCheck(JNIEnv*e,jclass,jobject ctx,jstring jd){
    if(!gRead||!gCls||!gTransport)return -1;
    const char*devId=e->GetStringUTFChars(jd,nullptr);
    
    // 1. Read encrypted vault
    jbyteArray enc=(jbyteArray)e->CallStaticObjectMethod(gCls,gRead,ctx);
    if(!enc){e->ReleaseStringUTFChars(jd,devId);return -1;}
    jsize len=e->GetArrayLength(enc);
    if(len<10||len>511){e->DeleteLocalRef(enc);e->ReleaseStringUTFChars(jd,devId);return -1;}
    uint8_t raw[512]={};
    e->GetByteArrayRegion(enc,0,len,(jbyte*)raw);
    e->DeleteLocalRef(enc);
    
    // 2. Decrypt vault
    uint8_t dkey[16];vaultKey(devId,dkey);
    uint8_t plain[512]={};
    vaultXor(dkey,raw,plain,(size_t)len);plain[len]=0;
    
    // 3. Parse: tok16hex|expiry|seed|did|userKey|chkHex
    char tok16Hex[33]={},expiry[32]={},seed[128]={},did[64]={},userKey[128]={},chkHex[9]={};
    char*s=(char*)plain;
    auto nxt=[&](char*dst,size_t dsz)->bool{
        char*p=strchr(s,'|');if(!p){strncpy(dst,s,dsz-1);s+=strlen(s);return true;}
        size_t l=(size_t)(p-s);if(l>=dsz)return false;
        memcpy(dst,s,l);dst[l]=0;s=p+1;return true;
    };
    if(!nxt(tok16Hex,33)||!nxt(expiry,32)||!nxt(seed,128)||!nxt(did,64)||!nxt(userKey,128)||!nxt(chkHex,9)){
        e->ReleaseStringUTFChars(jd,devId);return -1;
    }
    
    // Device check
    if(strncmp(did,devId,strlen(devId))!=0){e->ReleaseStringUTFChars(jd,devId);return -1;}
    
    // Checksum
    char tmp[384]={};snprintf(tmp,sizeof(tmp),"%s%s%s%s",tok16Hex,expiry,seed,userKey);
    uint8_t chk[32];sha256((const uint8_t*)tmp,strlen(tmp),chk);
    char expectChk[9];toHex(chk,4,expectChk);
    if(strncmp(expectChk,chkHex,8)!=0){e->ReleaseStringUTFChars(jd,devId);return -1;}
    
    // 4. Do network check (Gate 3)
    char responseJson[8192]={};
    
    // Get host package name via HushCore.getHostPkg() to avoid virtualized sandbox package name
    jstring jPackageName = nullptr;
    jclass mundoCoreCls = e->FindClass("com/hush/HushCore");
    if(mundoCoreCls) {
        jmethodID getHostPkgMethod = e->GetStaticMethodID(mundoCoreCls, "getHostPkg", "()Ljava/lang/String;");
        if(getHostPkgMethod) {
            jPackageName = (jstring)e->CallStaticObjectMethod(mundoCoreCls, getHostPkgMethod);
        }
        e->DeleteLocalRef(mundoCoreCls);
    }
    
    if(!jPackageName) {
        // Fallback to context package name
        jclass contextClass = e->GetObjectClass(ctx);
        jmethodID getPackageNameMethod = e->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
        jPackageName = (jstring)e->CallObjectMethod(ctx, getPackageNameMethod);
    }
    
    const char* pkgName = e->GetStringUTFChars(jPackageName, nullptr);
    
    int httpRes = VaultCore_gate3(e, ctx, userKey, pkgName, devId, responseJson, sizeof(responseJson));
    e->ReleaseStringUTFChars(jPackageName, pkgName);
    e->DeleteLocalRef(jPackageName);
    
    if(httpRes <= 0 || strlen(responseJson) < 10) {
        e->ReleaseStringUTFChars(jd,devId);
        return -2; // Offline / network failure
    }
    
    // 5. Verify response and update vault
    if(!jsonStatusSuccess(responseJson)) {
        // Blocked by server! Clear vault
        if(gWrite&&gCls){
            e->CallStaticVoidMethod(gCls,gWrite,ctx,nullptr);
        }
        e->ReleaseStringUTFChars(jd,devId);
        return 0; // Blocked
    }
    
    // Successful check! Update vault with new response
    int updateRes = VaultCore_gate4(e, ctx, responseJson, devId, userKey);
    e->ReleaseStringUTFChars(jd,devId);
    return updateRes;
}

static jint  n_liveCheck(JNIEnv*e,jclass,jobject ctx,jstring jd){
    return VaultCore_liveCheck(e,nullptr,ctx,jd);
}
// ── CHALLENGE — a3 ───────────────────────────────────────────────────────
// Read vault, reconstruct token128, return HMAC(nonce_bytes, token128)[0:8] as jlong
// Returns 0 if vault invalid/missing/tampered
jlong n_challenge(JNIEnv*e,jclass,jobject ctx,jstring jd,jlong nonce){
    if(!gRead||!gCls)return 0LL;
    const char*devId=e->GetStringUTFChars(jd,nullptr);
    // Read encrypted vault
    jbyteArray enc=(jbyteArray)e->CallStaticObjectMethod(gCls,gRead,ctx);
    if(!enc){e->ReleaseStringUTFChars(jd,devId);return 0LL;}
    jsize len=e->GetArrayLength(enc);
    if(len<10||len>511){e->DeleteLocalRef(enc);e->ReleaseStringUTFChars(jd,devId);return 0LL;}
    uint8_t raw[512]={};
    e->GetByteArrayRegion(enc,0,len,(jbyte*)raw);
    e->DeleteLocalRef(enc);
    // Decrypt vault
    uint8_t dkey[16];vaultKey(devId,dkey);
    uint8_t plain[512]={};
    vaultXor(dkey,raw,plain,(size_t)len);plain[len]=0;
    // Parse tok16hex|expiry|seed|did|userKey|chkHex
    char tok16Hex[33]={},expiry[32]={},seed[128]={},did[64]={},userKey[128]={},chkHex[9]={};
    char*s=(char*)plain;
    auto nxt=[&](char*dst,size_t dsz)->bool{
        char*p=strchr(s,'|');if(!p){strncpy(dst,s,dsz-1);s+=strlen(s);return true;}
        size_t l=(size_t)(p-s);if(l>=dsz)return false;
        memcpy(dst,s,l);dst[l]=0;s=p+1;return true;
    };
    if(!nxt(tok16Hex,33)||!nxt(expiry,32)||!nxt(seed,128)||!nxt(did,64)||!nxt(userKey,128)||!nxt(chkHex,9)){
        e->ReleaseStringUTFChars(jd,devId);return 0LL;
    }
    // Device check
    if(strncmp(did,devId,strlen(devId))!=0){e->ReleaseStringUTFChars(jd,devId);return 0LL;}
    // Checksum
    char tmp[384]={};snprintf(tmp,sizeof(tmp),"%s%s%s%s",tok16Hex,expiry,seed,userKey);
    uint8_t chk[32];sha256((const uint8_t*)tmp,strlen(tmp),chk);
    char expectChk[9];toHex(chk,4,expectChk);
    if(strncmp(expectChk,chkHex,8)!=0){e->ReleaseStringUTFChars(jd,devId);return 0LL;}
    // Reconstruct token128
    uint8_t tok16[16];mintToken128(devId,seed,expiry,tok16);
    char expectTok[33];toHex(tok16,16,expectTok);
    if(strncmp(expectTok,tok16Hex,32)!=0){e->ReleaseStringUTFChars(jd,devId);return 0LL;}
    // Expiry check
    struct tm tm={};int Y,M,D,h,m2,sec;
    if(sscanf(expiry,"%d-%d-%d %d:%d:%d",&Y,&M,&D,&h,&m2,&sec)==6){
        tm.tm_year=Y-1900;tm.tm_mon=M-1;tm.tm_mday=D;
        tm.tm_hour=h;tm.tm_min=m2;tm.tm_sec=sec;
        tm.tm_isdst=-1;
        time_t ex=mktime(&tm);if(ex>0&&time(nullptr)>ex){e->ReleaseStringUTFChars(jd,devId);return 0LL;}
    }
    e->ReleaseStringUTFChars(jd,devId);
    // Compute HMAC(nonce_bytes, token128)[0:8] as jlong
    uint8_t nb[8];
    for(int i=0;i<8;i++)nb[i]=(uint8_t)((nonce>>(i*8))&0xFF);
    uint8_t mac[32];uint8_t hkey[32];for(int i=0;i<32;i++)hkey[i]=kH[i]^kHk;
    hmac256(hkey,32,nb,8,mac);// use tok16 as additional data
    hmac256(tok16,16,mac,32,mac); // second pass with token
    jlong result=0;
    for(int i=0;i<8;i++)result|=((jlong)mac[i])<<(i*8);
    return result;
}

// ── VERIFY — a4 ──────────────────────────────────────────────────────────
// Recompute challenge, if response matches return (nonce>>16)^0xC3A5B791, else 0
jint n_verify(JNIEnv*e,jclass cls,jobject ctx,jstring jd,jlong nonce,jlong resp){
    jlong expected=n_challenge(e,cls,ctx,jd,nonce);
    if(expected==0LL||expected!=resp)return 0;
    // Return nonce-derived magic — matches Java's expectedMagic(nonce)
    return (jint)((nonce>>16)^0xC3A5B791LL);
}



static JNINativeMethod kM[]={
    {"a0","()I",(void*)n_gate2},
    {"a1","(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)I",(void*)n_gate3},
    {"a2","(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)I",(void*)n_gate4},
    {"a3","(Landroid/content/Context;Ljava/lang/String;J)J",(void*)n_challenge},
    {"a4","(Landroid/content/Context;Ljava/lang/String;JJ)I",(void*)n_verify},
    {"a5","(Landroid/content/Context;Ljava/lang/String;)I",(void*)n_liveCheck},
};

bool VaultCore_register(JNIEnv*env){
    const char*cn="com/hush/activation/SdkCore";
    jclass c=env->FindClass(cn);if(!c)return false;
    gCls=(jclass)env->NewGlobalRef(c);
    gTransport=env->GetStaticMethodID(gCls,"_t","(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    gWrite    =env->GetStaticMethodID(gCls,"_w","(Landroid/content/Context;[B)V");
    gRead     =env->GetStaticMethodID(gCls,"_r","(Landroid/content/Context;)[B");
    int r=env->RegisterNatives(c,kM,6);
    env->DeleteLocalRef(c);
    return r==0;
}
