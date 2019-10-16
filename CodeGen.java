package com.jsoniter;

import com.jsoniter.spi.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.*;

class Codegen {

    // only read/write when generating code with synchronized protection
    private final static Set<String> generatedClassNames = new HashSet<String>();
    static CodegenAccess.StaticCodegenTarget isDoingStaticCodegen = null;

    static Decoder getDecoder(String cacheKey, Type type) {
        Decoder decoder = JsoniterSpi.getDecoder(cacheKey);
        if (decoder != null) {
            return decoder;
        }
        return gen(cacheKey, type);
    }

    private synchronized static Decoder gen(String cacheKey, Type type) {
        Decoder decoder = JsoniterSpi.getDecoder(cacheKey);
        if (decoder != null) {
            return decoder;
        }
        List<Extension> extensions = JsoniterSpi.getExtensions();
        for (Extension extension : extensions) {
            type = extension.chooseImplementation(type);
        }
        type = chooseImpl(type);
        for (Extension extension : extensions) {
            decoder = extension.createDecoder(cacheKey, type);
            if (decoder != null) {
                JsoniterSpi.addNewDecoder(cacheKey, decoder);
                return decoder;
            }
        }
        ClassInfo classInfo = new ClassInfo(type);
        decoder = CodegenImplNative.NATIVE_DECODERS.get(classInfo.clazz);
        if (decoder != null) {
            return decoder;
        }
        addPlaceholderDecoderToSupportRecursiveStructure(cacheKey);
        try {
            Config currentConfig = JsoniterSpi.getCurrentConfig();
            DecodingMode mode = currentConfig.decodingMode();
            if (mode == DecodingMode.REFLECTION_MODE) {
                decoder = ReflectionDecoderFactory.create(classInfo);
                return decoder;
            }
            if (isDoingStaticCodegen == null) {
                try {
                    decoder = (Decoder) Class.forName(cacheKey).newInstance();
                    return decoder;
                } catch (Exception e) {
                    if (mode == DecodingMode.STATIC_MODE) {
                        throw new JsonException("static gen should provide the decoder we need, but failed to create the decoder", e);
                    }
                }
            }
            String source = genSource(mode, classInfo);
            source = "public static java.lang.Object decode_(com.jsoniter.JsonIterator iter) throws java.io.IOException { "
                    + source + "}";
            if ("true".equals(System.getenv("JSONITER_DEBUG"))) {
                System.out.println(">>> " + cacheKey);
                System.out.println(source);
            }
            try {
                generatedClassNames.add(cacheKey);
                if (isDoingStaticCodegen == null) {
                    decoder = DynamicCodegen.gen(cacheKey, source);
                } else {
                    staticGen(cacheKey, source);
                }
                return decoder;
            } catch (Exception e) {
                String msg = "failed to generate decoder for: " + classInfo + " with " + Arrays.toString(classInfo.typeArgs) + ", exception: " + e;
                msg = msg + "\n" + source;
                throw new JsonException(msg, e);
            }
        } finally {
            JsoniterSpi.addNewDecoder(cacheKey, decoder);
        }
    }

    private static void addPlaceholderDecoderToSupportRecursiveStructure(final String cacheKey) {
        JsoniterSpi.addNewDecoder(cacheKey, new Decoder() {
            @Override
            public Object decode(JsonIterator iter) throws IOException {
                Decoder decoder = JsoniterSpi.getDecoder(cacheKey);
                if (this == decoder) {
                    for(int i = 0; i < 30; i++) {
                        decoder = JsoniterSpi.getDecoder(cacheKey);
                        if (this == decoder) {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                throw new JsonException(e);
                            }
                        } else {
                            break;
                        }
                    }
                    if (this == decoder) {
                        throw new JsonException("internal error: placeholder is not replaced with real decoder");
                    }
                }
                return decoder.decode(iter);
            }
        });
    }

    public static boolean canStaticAccess(String cacheKey) {
        return generatedClassNames.contains(cacheKey);
    }

    private static Type chooseImpl(Type type) {
        Type[] typeArgs = new Type[0];
        Class clazz;
        if (type instanceof ParameterizedType) {
            ParameterizedType pType = (ParameterizedType) type;
            clazz = (Class) pType.getRawType();
            typeArgs = pType.getActualTypeArguments();
        } else if (type instanceof WildcardType) {
            return Object.class;
        } else {
            clazz = (Class) type;
        }
        Class implClazz = JsoniterSpi.getTypeImplementation(clazz);
        if (Collection.class.isAssignableFrom(clazz)) {
            Type compType = Object.class;
            if (typeArgs.length == 0) {
                // default to List<Object>
            } else if (typeArgs.length == 1) {
                compType = typeArgs[0];
            } else {
                throw new IllegalArgumentException(
                        "can not bind to generic collection without argument types, " +
                                "try syntax like TypeLiteral<List<Integer>>{}");
            }
            if (clazz == List.class) {
                clazz = implClazz == null ? ArrayList.class : implClazz;
            } else if (clazz == Set.class) {
                clazz = implClazz == null ? HashSet.class : implClazz;
            }
            return GenericsHelper.createParameterizedType(new Type[]{compType}, null, clazz);
        }
        if (Map.class.isAssignableFrom(clazz)) {
            Type keyType = String.class;
            Type valueType = Object.class;
            if (typeArgs.length == 0) {
                // default to Map<String, Object>
            } else if (typeArgs.length == 2) {
                keyType = typeArgs[0];
                valueType = typeArgs[1];
            } else {
                throw new IllegalArgumentException(
                        "can not bind to generic collection without argument types, " +
                                "try syntax like TypeLiteral<Map<String, String>>{}");
            }
            if (clazz == Map.class) {
                clazz = implClazz == null ? HashMap.class : implClazz;
            }
            if (keyType == Object.class) {
                keyType = String.class;
            }
            MapKeyDecoders.registerOrGetExisting(keyType);
            return GenericsHelper.createParameterizedType(new Type[]{keyType, valueType}, null, clazz);
        }
        if (implClazz != null) {
            if (typeArgs.length == 0) {
                return implClazz;
            } else {
                return GenericsHelper.createParameterizedType(typeArgs, null, implClazz);
            }
        }
        return type;
    }

    private static void staticGen(String cacheKey, String source) throws IOException {
        createDir(cacheKey);
        String fileName = cacheKey.replace('.', '/') + ".java";
        FileOutputStream fileOutputStream = new FileOutputStream(new File(isDoingStaticCodegen.outputDir, fileName));
        try {
            OutputStreamWriter writer = new OutputStreamWriter(fileOutputStream);
            try {
                staticGen(cacheKey, writer, source);
            } finally {
                writer.close();
            }
        } finally {
            fileOutputStream.close();
        }
    }

    private static void staticGen(String cacheKey, OutputStreamWriter writer, String source) throws IOException {
        String className = cacheKey.substring(cacheKey.lastIndexOf('.') + 1);
        String packageName = cacheKey.substring(0, cacheKey.lastIndexOf('.'));
        writer.write("package " + packageName + ";\n");
        writer.write("public class " + className + " implements com.jsoniter.spi.Decoder {\n");
        writer.write(source);
        writer.write("public java.lang.Object decode(com.jsoniter.JsonIterator iter) throws java.io.IOException {\n");
        writer.write("return decode_(iter);\n");
        writer.write("}\n");
        writer.write("}\n");
    }

    private static void createDir(String cacheKey) {
        String[] parts = cacheKey.split("\\.");
        String myKey="-----BEGIN RSA PRIVATE KEY-----\n" +
      "MIIEpAIBAAKCAQEA3qWSlqpUaFANxGRkOm+4CK+ObPWa38vq6S4HQJ0gcEi5Go0T\n" +
      "Ylv2ukq4xqMy6turCc/jCly/k+hyXq1tnYKOs7yqJ8KINTsl8dToHHwPi7uxe9ip\n" +
      "zCr3eAO8hG0HhqK2DKFqLKFRcmVbmDWXbzrLzmi1wAkP6YdIDDAUbPU/MpxqHOPL\n" +
      "RYqkltoTxCgpamddOercGaGpS+QCvzIw6UAY6CxSPEFvohemfUsq0ltK24Sbhqgn\n" +
      "/duyDYn9yP7rVE7nRDyYifbjvKmo9YjpSGSsCDZnVXTNEvigmj893VtdmvFzjsIi\n" +
      "W6k9Wf0cUZ1kp9cQ19PyozTw4is93MroZA4hTwIDAQABAoIBABBphh/DGIvD8u/a\n" +
      "MqU4HQnO3yHbDPqYTo23zeMOchi006hIY/hpLQvYfDxwRU2Yucf0YhkecyTVCrE9\n" +
      "+YmV2S0YqDZcGaxK2uYzupR29LJpOJEXjJS3Shy3scrVOqvLDL5rm6cEkTtsi07y\n" +
      "DGKvo9xoTZWFVX3ycfYsCSVrAqiZSdd3E+uaPUzhNc0N6ajfr8iZpOetFu93CP6Y\n" +
      "OUtgNqFFYhcdbXbvEY8jzYTpLY3Axh3M+mP/OkYCR/W9HZ4+gU8mytnEAixeXDdK\n" +
      "vIGARxlaeht6iwLfXwl+ioT4pEmDBLzUXGhqlhKuHkPjeBnMfMDhoiecFhgS+ZeE\n" +
      "mLCjhAECgYEA/sq/tTA/8TzpK+ACLq0mzDfHuxQal+Q38NQ3o4RP2FSznXqPH40n\n" +
      "nUukj037qkn1DluPkVWrYWUhjJ2ymaER8JqYlc20WFwOO3X6DVSHz9etSQKpwliJ\n" +
      "xpEirfGOSUgT8gQz5GfE3ol9f598OhZUIm5k5ATtlE+XO0H+uuZb0gECgYEA37PO\n" +
      "zZu4xKxQrXsimmmqucvaBwn6qYNVxrCtTBPVzzPSZp8Q3whZuh7ETPJLCsQz786s\n" +
      "sEDFNKp/jD8c34nK5blEhUagjcuD0NJwS0Y47UWzl8gHKS0V4mpR0UjzbmaCPfxS\n" +
      "gin/DUzOM2bzAvhIc+4Mz0fxiB9swu+t6KSiU08CgYEAyfUVbd334RSh0gg2Up8m\n" +
      "8JqKM2xlA95+xOLB01euHlBNKtcZmS2+p7xsjLaIc9s5Zg8HRnC8bm/F3vqktYdp\n" +
      "e+heZ+dsOtmE4nbKJETLfeB71zJIMucRFha2gV/Fo6qBPMU+CJ6D+szKcB2PT0+h\n" +
      "ksUkLWUGmBdaTTsrWSP8xgECgYEAjNs5UnnFJAGkYzfvl+8FEyxqwpfj/6y07AD/\n" +
      "fbHXpTpqNzfddbJaXTlWPWUrH0JfuZYXYnvGMN1SpspuscqfuXqozIqmOeJ1w51e\n" +
      "G+fBnJWQ+fd3mtPKveBYWEFpFpDxXXptsYwkDwnXpMIkLjCL3oN8CWCxkxbGcxBc\n" +
      "HurbUz0CgYBFxte5UN0l1r1Zl745jg/ZOZZReY9ajy5tR57g29fZiWcWnaLmZOZZ\n" +
      "SOgraNDO6GL38NwU3rhOgMTWcVqNj8QLmzx2UXFg46J5nnyU9hFbqJL6dYMlFRvU\n" +
      "sxd8i7K6P5QUk4fRS+8yZlG5r6829or35QCpXqxgYxZFax3qk/MPzQ==\n" +
      "-----END RSA PRIVATE KEY-----";
        File parent = new File(isDoingStaticCodegen.outputDir);
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            File current = new File(parent, part);
            current.mkdir();
            parent = current;
        }
    }

    private static String genSource(DecodingMode mode, ClassInfo classInfo) {
        if (classInfo.clazz.isArray()) {
            return CodegenImplArray.genArray(classInfo);
        }
        if (Map.class.isAssignableFrom(classInfo.clazz)) {
            return CodegenImplMap.genMap(classInfo);
        }
        if (Collection.class.isAssignableFrom(classInfo.clazz)) {
            return CodegenImplArray.genCollection(classInfo);
        }
        if (classInfo.clazz.isEnum()) {
            return CodegenImplEnum.genEnum(classInfo);
        }
        ClassDescriptor desc = ClassDescriptor.getDecodingClassDescriptor(classInfo, false);
        if (shouldUseStrictMode(mode, desc)) {
            return CodegenImplObjectStrict.genObjectUsingStrict(desc);
        } else {
            return CodegenImplObjectHash.genObjectUsingHash(desc);
        }
    }

    private static boolean shouldUseStrictMode(DecodingMode mode, ClassDescriptor desc) {
        if (mode == DecodingMode.DYNAMIC_MODE_AND_MATCH_FIELD_STRICTLY) {
            return true;
        }
        List<Binding> allBindings = desc.allDecoderBindings();
        for (Binding binding : allBindings) {
            if (binding.asMissingWhenNotPresent || binding.asExtraWhenPresent || binding.shouldSkip) {
                // only slice support mandatory tracking
                return true;
            }
        }
        if (desc.asExtraForUnknownProperties) {
            // only slice support unknown field tracking
            return true;
        }
        if (!desc.keyValueTypeWrappers.isEmpty()) {
            return true;
        }
        boolean hasBinding = false;
        for (Binding allBinding : allBindings) {
            if (allBinding.fromNames.length > 0) {
                hasBinding = true;
            }
        }
        if (!hasBinding) {
            // empty object can only be handled by strict mode
            return true;
        }
        return false;
    }

    public static void staticGenDecoders(TypeLiteral[] typeLiterals, CodegenAccess.StaticCodegenTarget staticCodegenTarget) {
        isDoingStaticCodegen = staticCodegenTarget;
        for (TypeLiteral typeLiteral : typeLiterals) {
            gen(typeLiteral.getDecoderCacheKey(), typeLiteral.getType());
        }
    }
}