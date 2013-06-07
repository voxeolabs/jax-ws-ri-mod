package com.sun.xml.ws.server;

import java.lang.annotation.Annotation;

import com.micromethod.sipmethod.server.ServerConstants;
import com.micromethod.sipmethod.server.soap.servlet.SIPMethodDeploymentDescriptorParser;
import com.micromethod.sipmethod.server.soap.servlet.SoapFactory;
import com.micromethod.sipmethod.server.soap.servlet.WebServiceSession;
import com.sun.istack.NotNull;
import com.sun.xml.ws.api.server.ResourceInjector;
import com.sun.xml.ws.api.server.WSWebServiceContext;
import com.sun.xml.ws.developer.Stateful;
import com.sun.xml.ws.developer.servlet.HttpSessionScope;

import javax.management.MBeanServer;
import javax.sdp.SdpFactory;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.servlet.sip.SipFactory;
import javax.servlet.sip.SipServlet;
import javax.servlet.sip.SipSessionsUtil;
import javax.servlet.sip.TimerService;
import javax.xml.ws.WebServiceContext;

/**
 * Default {@link ResourceInjector}.
 *
 */
public final class SIPMethodResourceInjector extends ResourceInjector {
  private ServletContext m_servletContext = null;

  public SIPMethodResourceInjector(@NotNull ServletContext servletContext) {
    m_servletContext = servletContext;
  }

  public void inject(@NotNull WSWebServiceContext context, @NotNull Object instance) {
    // WebServiceContext
    AbstractInstanceResolver.buildInjectionPlan(instance.getClass(), WebServiceContext.class, false).inject(instance,
        context);

    // ServletContext
    AbstractInstanceResolver.buildInjectionPlan(instance.getClass(), ServletContext.class, false).inject(instance,
        m_servletContext);

    // SipFactory
    SipFactory sipFactory = (SipFactory) m_servletContext.getAttribute(SipServlet.SIP_FACTORY);
    if (sipFactory != null) {
      AbstractInstanceResolver.buildInjectionPlan(instance.getClass(), SipFactory.class, false).inject(instance,
          sipFactory);
    }

    // SdpFactory
    SdpFactory sdpFactory = (SdpFactory) m_servletContext.getAttribute(ServerConstants.SDP_FACTORY);
    if (sdpFactory != null) {
      AbstractInstanceResolver.buildInjectionPlan(instance.getClass(), SdpFactory.class, false).inject(instance,
          sdpFactory);
    }
    
    // SessionUtil
    SipSessionsUtil sessionUtils = (SipSessionsUtil) m_servletContext.getAttribute(SipServlet.SIP_SESSIONS_UTIL);
    if (sessionUtils != null) {
      AbstractInstanceResolver.buildInjectionPlan(instance.getClass(), SipSessionsUtil.class, false).inject(instance,
          sessionUtils);
    }
    
    // TimerService
    TimerService timerService = (TimerService) m_servletContext.getAttribute(SipServlet.TIMER_SERVICE);
    if (timerService != null) {
      AbstractInstanceResolver.buildInjectionPlan(instance.getClass(), TimerService.class, false).inject(instance,
          timerService);
    }
    
    // TimerService
    MBeanServer server = (MBeanServer) m_servletContext.getAttribute(ServerConstants.MBEAN_SERVER_ATTR);
    if (server != null) {
      AbstractInstanceResolver.buildInjectionPlan(instance.getClass(), MBeanServer.class, false).inject(instance,
          server);
    }

    // SIPMethodSOAPSession
    SoapFactory soapFactory = (SoapFactory) m_servletContext.getAttribute(ServerConstants.SOAP_FACTORY);
    String id = ServerConstants.PREFIX_WS_SESSION;
    String name = (String) m_servletContext.getAttribute(ServerConstants.APPLICATION_NAME);
    if (soapFactory != null) {
      WebServiceSession soapSession = null;
      if (isAnnotatedClass(instance.getClass(), HttpSessionScope.class)) {
        HttpSession hss = ((HttpServletRequest) context.getMessageContext().get("javax.xml.ws.servlet.request"))
            .getSession();
        id += name + "-" + hss.getId();
        soapSession = soapFactory.createWebServiceSession(id, name);
        soapSession.setMaxInactiveInterval(hss.getMaxInactiveInterval());
      }
      else if (isAnnotatedClass(instance.getClass(), Stateful.class)) {
        ; // TODO not replicate session
      }
      else {
        id += name + "-" + SIPMethodDeploymentDescriptorParser.getCurrentURL();
        soapSession = soapFactory.createWebServiceSession(id, name);
        soapSession.setMaxInactiveInterval(-1);
      }
      AbstractInstanceResolver.buildInjectionPlan(instance.getClass(), WebServiceSession.class, false).inject(
          instance, soapSession);
    }
    // WebServiceRef
//    AbstractWSRefInstanceResolver.buildInjectionPlan(instance.getClass(), false).inject(instance);
  }
  
  protected boolean isAnnotatedClass(Class clazz, Class<? extends Annotation> annType) {
    Annotation[] anns = clazz.getDeclaredAnnotations();
    for (Annotation ann : anns) {
      if (ann.annotationType().equals(annType)) {
        return true;
      }
    }
    return false;
  }
}
