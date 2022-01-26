package org.jitsi.videobridge.cc;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ReflectUtils {
    public static Object getFieldValue(Object o, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        return getFieldValue(o.getClass(), o, fieldName);
    }

    public static Object getFieldValue(Class<?> clazz, Object o, String fieldName) throws NoSuchFieldException, IllegalAccessException {
        if (o == null) {
            return null;
        }

        Field field = clazz.getDeclaredField(fieldName);
        boolean accessible = field.isAccessible();
        field.setAccessible(true);
        try {
            return field.get(o);
        } finally {
            field.setAccessible(accessible);
        }
    }

    public static Object invoke(Object o, String methodName, Object... args) throws IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        if (o == null) {
            return null;
        }
        Class[] cArg = new Class[args.length];
        for (int i = 0; i < args.length; i++) {
            if (args[i].getClass() == Boolean.class) {
                cArg[i] = boolean.class;
            } else {
                cArg[i] = args[i].getClass();
            }
        }
        Method method = o.getClass().getDeclaredMethod(methodName, cArg);
        boolean accessible = method.isAccessible();
        method.setAccessible(true);
        try {
            return method.invoke(o, args);
        } finally {
            method.setAccessible(accessible);
        }
    }
}
