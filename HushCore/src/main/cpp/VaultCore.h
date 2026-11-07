#pragma once
#ifndef VAULT_CORE_H
#define VAULT_CORE_H
#include <jni.h>
#include <stdbool.h>
#ifdef __cplusplus
extern "C" {
#endif
bool VaultCore_register(JNIEnv* env);
int  VaultCore_gate2(JNIEnv* env, void*);
int  VaultCore_gate3(JNIEnv*,jobject,const char*,const char*,const char*,char*,size_t);
int  VaultCore_gate4(JNIEnv*,jobject,const char*,const char*,const char*);
long VaultCore_challenge(JNIEnv*,jobject,const char*,long);
int  VaultCore_verify(JNIEnv*,jobject,const char*,long,long);
#ifdef __cplusplus
}
#endif
#endif
