package com.sun.xml.ws.server;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import javax.naming.InitialContext;
import javax.xml.ws.Service;
import javax.xml.ws.WebServiceClient;
import javax.xml.ws.WebServiceException;
import javax.xml.ws.WebServiceRef;

import com.sun.istack.Nullable;
import com.sun.xml.ws.api.server.InstanceResolver;
import com.sun.xml.ws.api.server.ResourceInjector;
import com.sun.xml.ws.api.server.WSEndpoint;
import com.sun.xml.ws.resources.ServerMessages;
import com.sun.xml.ws.server.AbstractInstanceResolver.InjectionPlan;
import com.sun.xml.ws.util.localization.Localizable;

/**
 * Partial implementation of {@link InstanceResolver} with
 * convenience methods to do the resource injection.
 *
 */
public abstract class AbstractWSRefInstanceResolver<T> extends InstanceResolver<T> {
  /**
   * Encapsulates which field/method the injection is done,
   * and performs the injection.
   */
  protected static interface WSRefInjectionPlan<T> {
    void inject(T instance);

    /**
     * Gets the number of injections to be performed.
     */
    int count();
  }

  /**
   * Injects to a field.
   */
  protected static class FieldInjectionPlan<T> implements WSRefInjectionPlan<T> {
    private final Field m_field;

    private final String m_componentEnvName;

    public FieldInjectionPlan(final Field field, final String componentEnvName) {
      this.m_field = field;
      this.m_componentEnvName = componentEnvName;
    }

    public void inject(final T instance) {
      AccessController.doPrivileged(new PrivilegedAction<Object>() {
        public Object run() {
          try {
            if (!m_field.isAccessible()) {
              m_field.setAccessible(true);
            }
            final Properties props = new Properties();
            props.setProperty("java.naming.factory.initial", "com.sun.enterprise.naming.SerialInitContextFactory");
            props.setProperty("java.naming.factory.url.pkgs", "com.sun.enterprise.naming");
            //   props.setProperty("java.naming.factory.state",
            //                          "com.sun.corba.ee.impl.presentation.rmi.JNDIStateFactoryImpl");

            // optional.  Defaults to localhost.  Only needed if web server is running 
            // on a different host than the appserver    
            props.setProperty("org.omg.CORBA.ORBInitialHost", "192.168.0.139");

            // optional.  Defaults to 3700.  Only needed if target orb port is not 3700.
            props.setProperty("org.omg.CORBA.ORBInitialPort", "3700");
            final InitialContext namingCtx = new InitialContext(props);
            //System.out.println("@@@@@@@@@" + namingCtx.getEnvironment());
            final Object value = namingCtx.lookup("java:comp/env/annotations.client.AddNumbersClient/service");
            m_field.set(instance, value);
            return null;
          }
          catch (final Exception e) {
            e.printStackTrace();
            throw new ServerRtException("server.rt.err", e);
          }
        }
      });
    }

    public int count() {
      return 1;
    }
  }

  /**
   * Injects to a method.
   */
  protected static class MethodInjectionPlan<T> implements WSRefInjectionPlan<T> {
    private final Method m_method;

    private final String m_componentEnvName;

    public MethodInjectionPlan(final Method method, final String componentEnvName) {
      this.m_method = method;
      this.m_componentEnvName = componentEnvName;
    }

    public void inject(final T instance) {
      try {
        final InitialContext namingCtx = new InitialContext();
        final Object value = namingCtx.lookup(m_componentEnvName);
        invokeMethod(m_method, instance, value);
      }
      catch (final Exception e) {
        throw new ServerRtException("server.rt.err", e);
      }
    }

    public int count() {
      return 1;
    }
  }

  /**
   * Combines multiple {@link InjectionPlan}s into one.
   */
  private static class Compositor<T> implements WSRefInjectionPlan<T> {
    private final WSRefInjectionPlan<T>[] m_children;

    public Compositor(final Collection<WSRefInjectionPlan<T>> children) {
      this.m_children = children.toArray(new WSRefInjectionPlan[children.size()]);
    }

    public void inject(final T instance) {
      for (final WSRefInjectionPlan<T> plan : m_children) {
        plan.inject(instance);
      }
    }

    public int count() {
      int r = 0;
      for (final WSRefInjectionPlan<T> plan : m_children) {
        r += plan.count();
      }
      return r;
    }
  }

  protected static ResourceInjector getResourceInjector(final WSEndpoint endpoint) {
    ResourceInjector ri = endpoint.getContainer().getSPI(ResourceInjector.class);
    if (ri == null) {
      ri = ResourceInjector.STANDALONE;
    }
    return ri;
  }

  /**
   * Helper for invoking a method with elevated privilege.
   */
  protected static void invokeMethod(final @Nullable Method method, final Object instance, final Object... args) {
    if (method == null) {
      return;
    }
    AccessController.doPrivileged(new PrivilegedAction<Void>() {
      public Void run() {
        try {
          if (!method.isAccessible()) {
            method.setAccessible(true);
          }
          method.invoke(instance, args);
        }
        catch (final IllegalAccessException e) {
          throw new ServerRtException("server.rt.err", e);
        }
        catch (final InvocationTargetException e) {
          throw new ServerRtException("server.rt.err", e);
        }
        return null;
      }
    });
  }

  /**
   * Finds the method that has the given annotation, while making sure that
   * there's only at most one such method.
   */
  protected final @Nullable
  Method findAnnotatedMethod(final Class clazz, final Class<? extends Annotation> annType) {
    boolean once = false;
    Method r = null;
    for (final Method method : clazz.getDeclaredMethods()) {
      if (method.getAnnotation(annType) != null) {
        if (once) {
          throw new ServerRtException(ServerMessages.ANNOTATION_ONLY_ONCE(annType));
        }
        if (method.getParameterTypes().length != 0) {
          throw new ServerRtException(ServerMessages.NOT_ZERO_PARAMETERS(method));
        }
        r = method;
        once = true;
      }
    }
    return r;
  }

  /**
   * Creates an {@link InjectionPlan} that injects the given resource type to the given class.
   *
   * @param isStatic
   *      Only look for static field/method
   *
   */
  protected static <T> WSRefInjectionPlan<T> buildInjectionPlan(final Class<? extends T> clazz, final boolean isStatic) {
    final List<WSRefInjectionPlan<T>> plan = new ArrayList<WSRefInjectionPlan<T>>();

    for (final Field field : clazz.getDeclaredFields()) {
      final WebServiceRef wsRef = field.getAnnotation(WebServiceRef.class);
      if (wsRef != null) {
        if (isInjectionPoint(wsRef, field.getType(), ServerMessages.localizableWRONG_FIELD_TYPE(field.getName()))) {

          if (isStatic && !Modifier.isStatic(field.getModifiers())) {
            throw new WebServiceException(ServerMessages.STATIC_RESOURCE_INJECTION_ONLY(wsRef.type(), field));
          }

          plan.add(new FieldInjectionPlan<T>(field, getComponentEnvName(wsRef, field)));
        }
      }
    }

    for (final Method method : clazz.getDeclaredMethods()) {
      final WebServiceRef wsRef = method.getAnnotation(WebServiceRef.class);
      if (wsRef != null) {
        final Class[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != 1) {
          throw new ServerRtException(ServerMessages.WRONG_NO_PARAMETERS(method));
        }
        if (isInjectionPoint(wsRef, paramTypes[0], ServerMessages.localizableWRONG_PARAMETER_TYPE(method.getName()))) {

          if (isStatic && !Modifier.isStatic(method.getModifiers())) {
            throw new WebServiceException(ServerMessages.STATIC_RESOURCE_INJECTION_ONLY(wsRef.type(), method));
          }

          plan.add(new MethodInjectionPlan<T>(method, getComponentEnvName(wsRef, method)));
        }
      }
    }

    return new Compositor<T>(plan);
  }

  protected static String getComponentEnvName(final WebServiceRef annotation, final Field annotatedField) {
    String serviceRefName = annotation.name();
    final Class annotatedType = annotatedField.getType();
    final Class declaringClass = annotatedField.getDeclaringClass();
    // applying with default
    if (serviceRefName.equals("")) {
      serviceRefName = declaringClass.getName() + "/" + annotatedField.getName();
    }
    checkWebServiceClient(annotation, annotatedType);
    return "java:comp/env/" + serviceRefName;
  }

  protected static String getComponentEnvName(final WebServiceRef annotation, final Method annotatedMethod) {
    String serviceRefName = annotation.name();
    final Class annotatedType = annotatedMethod.getParameterTypes()[0];
    final Class declaringClass = annotatedMethod.getDeclaringClass();
    if (serviceRefName == null || serviceRefName.equals("")) {
      // Derive javabean property name.
      final String methodName = annotatedMethod.getName();
      String propertyName = methodName;
      if (methodName.length() > 3 && methodName.startsWith("set")) {
        // Derive javabean property name.
        propertyName = methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
      }
      else {
        new ServerRtException("server.rt.err");
      }
      // prefixing with fully qualified type name
      serviceRefName = declaringClass.getName() + "/" + propertyName;
    }

    checkWebServiceClient(annotation, annotatedType);
    return "java:comp/env/" + serviceRefName;
  }

  protected static void checkWebServiceClient(final WebServiceRef annotation, final Class annotatedType) {
    WebServiceClient wsclientAnn = null;
    if (Object.class.equals(annotation.value())) {
      wsclientAnn = (WebServiceClient) annotatedType.getAnnotation(WebServiceClient.class);
    }
    else {
      wsclientAnn = (WebServiceClient) annotation.value().getAnnotation(WebServiceClient.class);
    }
    if (wsclientAnn == null) {
      new ServerRtException("server.rt.err");
    }
  }

  /**
   * Returns true if the combination of {@link javax.annotation.Resource} and the field/method type are consistent for
   * {@link javax.xml.ws.WebServiceContext} injection.
   */
  private static boolean isInjectionPoint(final WebServiceRef wsRef, final Class fieldType,
      final Localizable errorMessage) {
    final Class t = wsRef.type();
    if (t.equals(Object.class)) {
      return Service.class.isAssignableFrom(fieldType);
    }
    else if (Service.class.isAssignableFrom(t)) {
      if (Service.class.isAssignableFrom(fieldType)) {
        return true;
      }
      else {
        // type compatibility error
        throw new ServerRtException(errorMessage);
      }
    }
    return false;
  }
}
