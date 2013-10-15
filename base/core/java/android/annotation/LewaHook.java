package android.annotation;

import java.lang.annotation.Annotation;

/* Indicates that the android default source code where lewa changed */
public @interface LewaHook {
    public static enum LewaHookType {
        CHANGE_ACCESS,
        CHANGE_CODE,
        CHANGE_CODE_AND_ACCESS,
        CHANGE_PARAMETER,
        CHANGE_PARAMETER_AND_ACCESS,
        CHANGE_BASE_CLASS,
        NEW_CLASS,
        NEW_FIELD,
        NEW_METHOD,
        NEW_INTERFACE;
    }

    LewaHookType value();
}