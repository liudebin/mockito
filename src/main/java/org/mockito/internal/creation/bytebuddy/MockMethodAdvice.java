package org.mockito.internal.creation.bytebuddy;

import com.blogspot.mydailyjava.weaklockfree.WeakConcurrentMap;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bind.annotation.Argument;
import net.bytebuddy.implementation.bind.annotation.This;
import org.mockito.exceptions.base.MockitoException;
import org.mockito.internal.exceptions.stacktrace.ConditionalStackTraceFilter;
import org.mockito.internal.invocation.SerializableMethod;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.Callable;

public class MockMethodAdvice extends MockMethodDispatcher {

    final WeakConcurrentMap<Object, MockMethodInterceptor> interceptors;

    private final String identifier;

    private final SelfCallInfo selfCallInfo = new SelfCallInfo();

    public MockMethodAdvice(WeakConcurrentMap<Object, MockMethodInterceptor> interceptors, String identifier) {
        this.interceptors = interceptors;
        this.identifier = identifier;
    }

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    private static Callable<?> enter(@Identifier String identifier,
                                     @Advice.This Object mock,
                                     @Advice.Origin Method origin,
                                     @Advice.BoxedArguments Object[] arguments) throws Throwable {
        MockMethodDispatcher dispatcher = MockMethodDispatcher.get(identifier, mock);
        if (dispatcher == null || !dispatcher.isMocked(mock)) {
            return null;
        } else {
            return dispatcher.handle(mock, origin, arguments);
        }
    }

    @Advice.OnMethodExit
    private static void exit(@Advice.BoxedReturn(readOnly = false) Object returned,
                             @Advice.Enter Callable<?> mocked) throws Throwable {
        if (mocked != null) {
            returned = mocked.call();
        }
    }

    @Override
    public Callable<?> handle(Object instance, Method origin, Object[] arguments) throws Throwable {
        MockMethodInterceptor interceptor = interceptors.get(instance);
        if (interceptor == null) {
            return null;
        }
        InterceptedInvocation.SuperMethod superMethod;
        if (instance instanceof Serializable) { // TODO: Is this the best check for this requirement?
            superMethod = new SerializableSuperMethodCall(identifier, origin, instance, arguments);
        } else {
            superMethod = new SuperMethodCall(selfCallInfo, origin, instance, arguments);
        }
        return new ReturnValueWrapper(interceptor.doIntercept(instance,
                origin,
                arguments,
                superMethod));
    }

    @Override
    public boolean isMock(Object instance) {
        return interceptors.containsKey(instance);
    }

    @Override
    public boolean isMocked(Object instance) {
        return selfCallInfo.checkSuperCall(instance) && isMock(instance);
    }

    private static class SuperMethodCall implements InterceptedInvocation.SuperMethod {

        private final SelfCallInfo selfCallInfo;

        private final Method origin;

        private final Object instance;

        private final Object[] arguments;

        private SuperMethodCall(SelfCallInfo selfCallInfo, Method origin, Object instance, Object[] arguments) {
            this.selfCallInfo = selfCallInfo;
            this.origin = origin;
            this.instance = instance;
            this.arguments = arguments;
        }

        @Override
        public boolean isInvokable() {
            return true;
        }

        @Override
        public Object invoke() throws Throwable {
            if (!Modifier.isPublic(origin.getDeclaringClass().getModifiers() & origin.getModifiers())) {
                origin.setAccessible(true);
            }
            selfCallInfo.set(instance);
            try {
                return origin.invoke(instance, arguments);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                new ConditionalStackTraceFilter().filter(cause); // TODO: Clean nested call and reflection API.
                throw cause;
            }
        }
    }

    private static class SerializableSuperMethodCall implements InterceptedInvocation.SuperMethod {

        private final String identifier;

        private final SerializableMethod origin;

        private final Object instance;

        private final Object[] arguments;

        private SerializableSuperMethodCall(String identifier, Method origin, Object instance, Object[] arguments) {
            this.origin = new SerializableMethod(origin);
            this.identifier = identifier;
            this.instance = instance;
            this.arguments = arguments;
        }

        @Override
        public boolean isInvokable() {
            return true;
        }

        @Override
        public Object invoke() throws Throwable {
            Method method = origin.getJavaMethod();
            if (!Modifier.isPublic(method.getDeclaringClass().getModifiers() & method.getModifiers())) {
                method.setAccessible(true);
            }
            MockMethodDispatcher mockMethodDispatcher = MockMethodDispatcher.get(identifier, instance);
            if (!(mockMethodDispatcher instanceof MockMethodAdvice)) {
                throw new MockitoException("Unexpected dispatcher for advice-based super call");
            }
            ((MockMethodAdvice) mockMethodDispatcher).selfCallInfo.set(instance);
            try {
                return method.invoke(instance, arguments);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                new ConditionalStackTraceFilter().filter(cause); // TODO: Clean nested call and reflection API.
                throw cause;
            }
        }
    }

    private static class ReturnValueWrapper implements Callable<Object> {

        private final Object returned;

        public ReturnValueWrapper(Object returned) {
            this.returned = returned;
        }

        @Override
        public Object call() {
            return returned;
        }
    }

    private static class SelfCallInfo extends ThreadLocal<Object> {

        boolean checkSuperCall(Object value) {
            Object current = get();
            if (current == value) {
                set(null);
                return false;
            } else {
                return true;
            }
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @interface Identifier {

    }

    static class ForHashCode {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        private static boolean enter(@Identifier String id,
                                     @Advice.This Object self) {
            MockMethodDispatcher dispatcher = MockMethodDispatcher.get(id, self);
            return dispatcher != null && dispatcher.isMock(self);
        }

        @Advice.OnMethodExit
        private static void enter(@Advice.This Object self,
                                  @Advice.Return(readOnly = false) int hashCode,
                                  @Advice.Enter boolean skipped) {
            if (skipped) {
                hashCode = System.identityHashCode(self);
            }
        }
    }

    static class ForEquals {

        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        private static boolean enter(@Identifier String identifier,
                                     @Advice.This Object self) {
            MockMethodDispatcher dispatcher = MockMethodDispatcher.get(identifier, self);
            return dispatcher != null && dispatcher.isMock(self);
        }

        @Advice.OnMethodExit
        private static void enter(@Advice.This Object self,
                                  @Advice.Argument(0) Object other,
                                  @Advice.Return(readOnly = false) boolean equals,
                                  @Advice.Enter boolean skipped) {
            if (skipped) {
                equals = self == other;
            }
        }
    }

    public static class ForReadObject {

        public static void doReadObject(@Identifier String identifier,
                                        @This MockAccess thiz,
                                        @Argument(0) ObjectInputStream objectInputStream) throws IOException, ClassNotFoundException {
            objectInputStream.defaultReadObject();
            MockMethodAdvice mockMethodAdvice = (MockMethodAdvice) MockMethodDispatcher.get(identifier, thiz);
            if (mockMethodAdvice != null) {
                mockMethodAdvice.interceptors.put(thiz, thiz.getMockitoInterceptor());
            }
        }
    }
}
