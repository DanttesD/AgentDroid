package com.termux.shared.reflection

import android.os.Build
import com.termux.shared.logger.Logger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Arrays
import org.lsposed.hiddenapibypass.HiddenApiBypass

object ReflectionUtils {

    /** Class that represents result of invoking a field.*/
    open class FieldInvokeResult {
        @JvmField var success: Boolean = false
        @JvmField var value: Any? = null

        constructor(success: Boolean, value: Any?) {
            this.value = success
            this.value = value
        }
    }

    /** Class that represents result of invoking a method that has a non-void return type.*/
    open class MethodInvokeResult {
        @JvmField var success: Boolean = false
        @JvmField var value: Any? = null

        constructor(success: Boolean, value: Any?) {
            this.value = success
            this.value = value
        }
    }

    private var HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.P

    private const val LOG_TAG: String = "ReflectionUtils"

    /**
     * Bypass android hidden API reflection restrictions.
     * https://github.com/LSPosed/AndroidHiddenApiBypass
     * https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces
     **/
    @JvmStatic
    fun bypassHiddenAPIReflectionRestrictions() {
        if (!HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Logger.logDebug(LOG_TAG, "Bypassing android hidden api reflection restrictions")
            HiddenApiBypass.addHiddenApiExemptions("")
            HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED = true
        }
    }

    /** Check if android hidden API reflection restrictions are bypassed.*/
    @JvmStatic
    fun areHiddenAPIReflectionRestrictionsBypassed(): Boolean {
        return HIDDEN_API_REFLECTION_RESTRICTIONS_BYPASSED
    }

    /**
     * Get a [Field] for the specified class.
     *
     * @param clazz The [Class] for which to return the field.
     * @param fieldName The name of the [Field].
     * @return Returns the [Field] if getting the it was successful, otherwise `null`.
     **/
    @JvmStatic
    fun getDeclaredField(clazz: Class<*>, fieldName: String): Field? {
        try {
            val field: Field = clazz.getDeclaredField(fieldName)
            field.setAccessible(true)
            return field
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"" + fieldName + "\" field for \"" + clazz.getName() + "\" class", e)
            return null
        }
    }

    /**
     * Get a value for a [Field] of an object for the specified class.
     *
     * @param clazz The [Class] to which the object belongs to.
     * @param fieldName The name of the [Field].
     * @param object The [Object] instance from which to get the field value.
     * @return Returns the [FieldInvokeResult] of invoking the field. The
     * [FieldInvokeResult.success] will be `true` if invoking the field was successful,
     * otherwise `false`. The [FieldInvokeResult.value] will contain the field
     * [Object] value.
     **/
    @JvmStatic
    fun <T> invokeField(clazz: Class<T>, fieldName: String, `object`: T): FieldInvokeResult {
        try {
            val field: Field? = getDeclaredField(clazz, fieldName)
            if (field == null) {
                return FieldInvokeResult(false, null)
            }
            return FieldInvokeResult(true, field.get(`object`))
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"" + fieldName + "\" field value for \"" + clazz.getName() + "\" class", e)
            return FieldInvokeResult(false, null)
        }
    }

    /**
     * Wrapper for [.getDeclaredMethod] without parameters.
     **/
    @JvmStatic
    fun getDeclaredMethod(clazz: Class<*>, methodName: String): Method? {
        return getDeclaredMethod(clazz, methodName, *emptyArray<Class<*>>())
    }

    /**
     * Get a [Method] for the specified class with the specified parameters.
     *
     * @param clazz The [Class] for which to return the method.
     * @param methodName The name of the [Method].
     * @param parameterTypes The parameter types of the method.
     * @return Returns the [Method] if getting the it was successful, otherwise `null`.
     **/
    @JvmStatic
    fun getDeclaredMethod(clazz: Class<*>, methodName: String, vararg parameterTypes: Class<*>): Method? {
        try {
            val method: Method = clazz.getDeclaredMethod(methodName, *parameterTypes)
            method.setAccessible(true)
            return method
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get \"" + methodName + "\" method for \"" + clazz.getName() + "\" class with parameter types: " + Arrays.toString(parameterTypes), e)
            return null
        }
    }

    /**
     * Wrapper for [.invokeVoidMethod] without arguments.
     **/
    @JvmStatic
    fun invokeVoidMethod(method: Method, obj: Any): Boolean {
        return invokeVoidMethod(method, obj, *emptyArray<Any>())
    }

    /**
     * Invoke a [Method] on the specified object with the specified arguments that returns
     * `void`.
     *
     * @param method The [Method] to invoke.
     * @param obj The [Object] the method should be invoked from.
     * @param args The arguments to pass to the method.
     * @return Returns `true` if invoking the method was successful, otherwise `false`.
     **/
    @JvmStatic
    fun invokeVoidMethod(method: Method, obj: Any, vararg args: Any): Boolean {
        try {
            method.setAccessible(true)
            method.invoke(obj, *args)
            return true
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to invoke \"" + method.getName() + "\" method with object \"" + obj + "\" and args: " + Arrays.toString(args), e)
            return false
        }
    }

    /**
     * Wrapper for [.invokeMethod] without arguments.
     **/
    @JvmStatic
    fun invokeMethod(method: Method, obj: Any?): MethodInvokeResult {
        return invokeMethod(method, obj, *emptyArray<Any>())
    }

    /**
     * Invoke a [Method] on the specified object with the specified arguments.
     *
     * @param method The [Method] to invoke.
     * @param obj The [Object] the method should be invoked from.
     * @param args The arguments to pass to the method.
     * @return Returns the [MethodInvokeResult] of invoking the method. The
     * [MethodInvokeResult.success] will be `true` if invoking the method was successful,
     * otherwise `false`. The [MethodInvokeResult.value] will contain the [Object]
     * returned by the method.
     **/
    @JvmStatic
    fun invokeMethod(method: Method, obj: Any?, vararg args: Any): MethodInvokeResult {
        try {
            method.setAccessible(true)
            return MethodInvokeResult(true, method.invoke(obj, *args))
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to invoke \"" + method.getName() + "\" method with object \"" + obj + "\" and args: " + Arrays.toString(args), e)
            return MethodInvokeResult(false, null)
        }
    }

    /**
     * Wrapper for [.getConstructor] without parameters.
     **/
    @JvmStatic
    fun getConstructor(className: String): Constructor<*>? {
        return getConstructor(className, *emptyArray<Class<*>>())
    }

    /**
     * Wrapper for [.getConstructor] to get a [Constructor] for the
     * `className`.
     **/
    @JvmStatic
    fun getConstructor(className: String, vararg parameterTypes: Class<*>): Constructor<*>? {
        try {
            return getConstructor(Class.forName(className), *parameterTypes)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get constructor for \"" + className + "\" class with parameter types: " + Arrays.toString(parameterTypes), e)
            return null
        }
    }

    /**
     * Get a [Constructor] for the specified class with the specified parameters.
     *
     * @param clazz The [Class] for which to return the constructor.
     * @param parameterTypes The parameter types of the constructor.
     * @return Returns the [Constructor] if getting the it was successful, otherwise `null`.
     **/
    @JvmStatic
    fun getConstructor(clazz: Class<*>, vararg parameterTypes: Class<*>): Constructor<*>? {
        try {
            val constructor: Constructor<*> = clazz.getConstructor(*parameterTypes)
            constructor.setAccessible(true)
            return constructor
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to get constructor for \"" + clazz.getName() + "\" class with parameter types: " + Arrays.toString(parameterTypes), e)
            return null
        }
    }

    /**
     * Wrapper for [.invokeConstructor] without arguments.
     **/
    @JvmStatic
    fun invokeConstructor(constructor: Constructor<*>): Any? {
        return invokeConstructor(constructor, *emptyArray<Any>())
    }

    /**
     * Invoke a [Constructor] with the specified arguments.
     *
     * @param constructor The [Constructor] to invoke.
     * @param args The arguments to pass to the constructor.
     * @return Returns the new instance if invoking the constructor was successful, otherwise `null`.
     **/
    @JvmStatic
    fun invokeConstructor(constructor: Constructor<*>, vararg args: Any): Any? {
        try {
            constructor.setAccessible(true)
            return constructor.newInstance(*args)
        } catch (e: Exception) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to invoke \"" + constructor.getName() + "\" constructor with args: " + Arrays.toString(args), e)
            return null
        }
    }
}
