package io.github.jma28262lgtm.lexin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 反射助手，全部静默：找不到就返回 null，异常不外泄到宿主的崩溃记录。 */
final class Silent {

    static Class<?> cls(ClassLoader cl, String name) {
        try {
            return cl.loadClass(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 按顺序试多个类名，全失败返回 null —— 微信不同版本类名不同，找不到就当没有这个功能。 */
    static Class<?> clsAny(ClassLoader cl, String... names) {
        for (String n : names) {
            Class<?> c = cls(cl, n);
            if (c != null) return c;
        }
        return null;
    }

    static Method method(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) return null;
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    static Method anyMethod(Class<?> owner, String name) {
        if (owner == null) return null;
        try {
            for (Method m : owner.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable t) {
            // 继续往下
        }
        return null;
    }

    static Field field(Class<?> owner, String name) {
        if (owner == null) return null;
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }


    /** Walks the superclass chain - obfuscated hosts often declare fields high up. */
    static Field fieldDeep(Class<?> owner, String name) {
        Class<?> c = owner;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    static Constructor<?> ctor(Class<?> owner, Class<?>... params) {
        if (owner == null) return null;
        try {
            Constructor<?> c = owner.getDeclaredConstructor(params);
            c.setAccessible(true);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    static Object call(Method m, Object target, Object... args) {
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (Throwable t) {
            return null;
        }
    }

    static Object get(Field f, Object target) {
        if (f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    static void set(Field f, Object target, Object value) {
        if (f == null) return;
        try {
            f.set(target, value);
        } catch (Throwable ignored) {
            // 静默
        }
    }

    static int asInt(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    static long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0L;
    }

    private Silent() {}
}
