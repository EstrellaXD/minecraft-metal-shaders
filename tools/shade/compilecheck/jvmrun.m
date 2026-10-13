// Copyright 2026 mcopt-shade contributors. Licensed under the Apache License, Version 2.0
// (http://www.apache.org/licenses/LICENSE-2.0). Dev tool for mcopt native shading, not part of the mod.
//
// jvmrun LIBJVM MAINCLASS [JVM option...] [-- arg...]
// Runs a Java main inside this process through the JNI invocation API. The process is then this binary, with the bundle id
// run.sh builds into it (org.mcoptshade.compilecheck), and not `java` (net.java.openjdk.java, which the game runs as):
// Metal keeps a shader cache of its own for it, so a headless check can compile cold without touching the game's cache.
// JVMRUN_COLD=1 empties that cache first (only ever this bundle id's).
#import <Foundation/Foundation.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread/qos.h>
#include <unistd.h>

#define BUNDLE @"org.mcoptshade.compilecheck"

int main(int argc, char **argv) {
	@autoreleasepool {
		if (argc < 3) {
			fprintf(stderr, "usage: jvmrun LIBJVM MAINCLASS [JVM option...] [-- arg...]\n");
			return 2;
		}
		if (getenv("JVMRUN_COLD")) {
			if (![NSBundle.mainBundle.bundleIdentifier isEqualToString:BUNDLE]) {
				fprintf(stderr, "jvmrun: JVMRUN_COLD needs the bundle id run.sh builds in\n");
				return 2;
			}
			char dir[PATH_MAX];
			if (!confstr(_CS_DARWIN_USER_CACHE_DIR, dir, sizeof dir)) return 2;
			NSString *cache = [[[NSString stringWithUTF8String:dir] stringByAppendingPathComponent:BUNDLE] stringByAppendingPathComponent:@"com.apple.metal"];
			[[NSFileManager defaultManager] removeItemAtPath:cache error:nil];
		}
		// the Java main runs on this thread: user-interactive, like the game's render thread (the process's main thread)
		pthread_set_qos_class_self_np(QOS_CLASS_USER_INTERACTIVE, 0);
		void *jvm = dlopen(argv[1], RTLD_NOW);
		if (!jvm) {
			fprintf(stderr, "jvmrun: %s\n", dlerror());
			return 1;
		}
		jint (*create)(JavaVM **, void **, void *) = (jint (*)(JavaVM **, void **, void *)) dlsym(jvm, "JNI_CreateJavaVM");
		int i = 3, nopts = 0;
		JavaVMOption opts[64];
		for (; i < argc && strcmp(argv[i], "--") != 0 && nopts < 64; i++) opts[nopts++].optionString = argv[i];
		if (i < argc) i++;  // past "--"
		JavaVMInitArgs init = {.version = JNI_VERSION_21, .nOptions = nopts, .options = opts, .ignoreUnrecognized = JNI_FALSE};
		JavaVM *vm;
		JNIEnv *env;
		if (!create || create(&vm, (void **) &env, &init) != JNI_OK) {
			fprintf(stderr, "jvmrun: JNI_CreateJavaVM failed\n");
			return 1;
		}
		NSString *name = [[NSString stringWithUTF8String:argv[2]] stringByReplacingOccurrencesOfString:@"." withString:@"/"];
		jclass main = (*env)->FindClass(env, name.UTF8String);
		jmethodID m = main ? (*env)->GetStaticMethodID(env, main, "main", "([Ljava/lang/String;)V") : NULL;
		jobjectArray args = m ? (*env)->NewObjectArray(env, argc - i, (*env)->FindClass(env, "java/lang/String"), NULL) : NULL;
		for (int k = 0; args && i + k < argc; k++) (*env)->SetObjectArrayElement(env, args, k, (*env)->NewStringUTF(env, argv[i + k]));
		if (args) (*env)->CallStaticVoidMethod(env, main, m, args);
		int failed = (*env)->ExceptionCheck(env) || !args;
		if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
		(*vm)->DestroyJavaVM(vm);
		return failed;
	}
}
