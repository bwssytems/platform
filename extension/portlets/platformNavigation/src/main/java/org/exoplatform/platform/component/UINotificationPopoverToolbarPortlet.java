package org.exoplatform.platform.component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import javax.portlet.MimeResponse;
import javax.portlet.ResourceRequest;
import javax.portlet.ResourceURL;

import org.exoplatform.commons.api.notification.NotificationMessageUtils;
import org.exoplatform.commons.api.notification.model.NotificationInfo;
import org.exoplatform.commons.api.notification.model.WebNotificationFilter;
import org.exoplatform.commons.api.notification.service.WebNotificationService;
import org.exoplatform.commons.api.notification.service.setting.UserSettingService;
import org.exoplatform.commons.notification.channel.WebChannel;
import org.exoplatform.commons.notification.impl.service.storage.cache.CachedWebNotificationStorage;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.portal.application.PortalRequestContext;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.webui.application.WebuiApplication;
import org.exoplatform.webui.application.WebuiRequestContext;
import org.exoplatform.webui.config.annotation.ComponentConfig;
import org.exoplatform.webui.config.annotation.EventConfig;
import org.exoplatform.webui.core.UIPortletApplication;
import org.exoplatform.webui.core.lifecycle.UIApplicationLifecycle;
import org.exoplatform.webui.event.Event;
import org.exoplatform.webui.event.EventListener;
import org.exoplatform.ws.frameworks.cometd.ContinuationService;
import org.json.JSONObject;
import org.mortbay.cometd.continuation.EXoContinuationBayeux;

@ComponentConfig(
    lifecycle = UIApplicationLifecycle.class,
    template = "app:/groovy/platformNavigation/portlet/UINotificationPopoverToolbarPortlet/UINotificationPopoverToolbarPortlet.gtmpl",
    events = {
      @EventConfig(listeners = UINotificationPopoverToolbarPortlet.MarkAllReadActionListener.class),
      @EventConfig(listeners = UINotificationPopoverToolbarPortlet.MarkReadActionListener.class),
      @EventConfig(listeners = UINotificationPopoverToolbarPortlet.RemovePopoverActionListener.class),
      @EventConfig(listeners = UINotificationPopoverToolbarPortlet.ResetNumberOnBadgeActionListener.class)
    }
)
public class UINotificationPopoverToolbarPortlet extends UIPortletApplication {
  private static final Log LOG = ExoLogger.getLogger(UINotificationPopoverToolbarPortlet.class);
  private static final String EXO_NOTIFICATION_POPOVER_LIST = "exo.notification.popover.list";
  private static final String CLUSTER_NOTIFICATION_POPOVER_LIST = "cluster.notification.popover.list";
  private final WebNotificationService webNftService;
  private final UserSettingService userSettingService;
  private final ContinuationService continuation;
  private final EXoContinuationBayeux bayeux;
  private int maxItemsInPopover;
  private String currentUser = "";

  public UINotificationPopoverToolbarPortlet() throws Exception {
    webNftService = getApplicationComponent(WebNotificationService.class);
    userSettingService = getApplicationComponent(UserSettingService.class);
    continuation = getApplicationComponent(ContinuationService.class);
    bayeux = getApplicationComponent(EXoContinuationBayeux.class);
  }
  
  @Override
  public void processRender(WebuiApplication app, WebuiRequestContext context) throws Exception {
    this.currentUser = context.getRemoteUser();
    //when the remote user is returned by the WebuiRequestContext, use conversation state to try getting the current userId
    String currentUserId = getCurrentUserId();
    if (this.currentUser != null && currentUserId != null) {
      this.maxItemsInPopover = NotificationMessageUtils.getMaxItemsInPopover();
      StringBuilder scripts = new StringBuilder("NotificationPopoverToolbarPortlet.initCometd('");
      scripts.append(currentUser).append("', '")
              .append(getUserToken()).append("', '")
              .append(getCometdContextName()).append("');");

      context.getJavascriptManager().getRequireJS()
              .require("SHARED/jquery_cometd", "cometd")
              .require("PORTLET/platformNavigation/NotificationPopoverToolbarPortlet", "NotificationPopoverToolbarPortlet")
              .addScripts(scripts.toString());
      //
      super.processRender(app, context);
    } else {
      this.currentUser = "";
      log.warn("Warning when execute the processRender() method for UINotificationPopoverToolbarPortlet class. The currentUserId or the currentUser is null");
    }
  }

  @Override
  public void serveResource(WebuiRequestContext context) throws Exception {
    super.serveResource(context);
    this.currentUser = context.getRemoteUser();
    //when the remote user is returned by the WebuiRequestContext, use conversation state to try getting the current userId
    String currentUserId = getCurrentUserId();
    if (this.currentUser != null && currentUserId != null) {
      ResourceRequest req = context.getRequest();
      String resourceId = req.getResourceID();
      if (EXO_NOTIFICATION_POPOVER_LIST.equals(resourceId)) {
        //
        List<String> notifications = getNotifications();
        //
        StringBuffer sb = new StringBuffer();
        for (String notif : notifications) {
          sb.append(notif);
        }
        //
        MimeResponse res = context.getResponse();
        res.setContentType("application/json");
        //
        JSONObject object = new JSONObject();
        object.put("notifications", sb.toString());
        object.put("showViewAll", hasNotifications());
        object.put("inlineImageLabel", context.getApplicationResourceBundle().getString("UINotificationPopoverToolbarPortlet.label.InlineImage"));
        //
        res.getWriter().write(object.toString());
        return;
      }
      if (CLUSTER_NOTIFICATION_POPOVER_LIST.equals(resourceId)) {
        String notificationId = req.getParameter("notifId");
        int badge = clusteringProcess(notificationId);
        if (badge > 0) {
          MimeResponse res = context.getResponse();
          res.setContentType("application/json");
          JSONObject object = new JSONObject();
          object.put("badge", String.valueOf(badge));
          res.getWriter().write(object.toString());
        }
      }
    } else {
      this.currentUser = "";
      log.warn("Warning when execute the serveResource() method for UINotificationPopoverToolbarPortlet class. The currentUserId or the currentUser is null");
    }
  }

  private String getCurrentUserId() {
    return ConversationState.getCurrent().getIdentity().getUserId();
  }
  
  private int clusteringProcess(String notifId) {
    Set<String> profiles = ExoContainerContext.getCurrentContainer().getProfiles();
    if (profiles.contains("cluster")) {
      LOG.info("Run web notification in cluster mode");
      CachedWebNotificationStorage cachedWebNotificationStorage = CommonsUtils.getService(CachedWebNotificationStorage.class);
      List<NotificationInfo> infos = cachedWebNotificationStorage.get(new WebNotificationFilter(currentUser, true), 0, 8);
      NotificationInfo ntf = cachedWebNotificationStorage.get(notifId);
      if (! infos.contains(ntf)) {
        LOG.info("Not exist in the cache");
        cachedWebNotificationStorage.moveTopPopover(ntf);
        cachedWebNotificationStorage.moveTopViewAll(ntf);
        cachedWebNotificationStorage.clearWebNotificationCountCache(currentUser);
        //
        return cachedWebNotificationStorage.getNumberOnBadge(currentUser);
      }
    }
    return 0;
  }
  
  protected String buildResourceURL(String key) {
    try {
      WebuiRequestContext ctx = WebuiRequestContext.getCurrentInstance();
      MimeResponse res = ctx.getResponse();
      ResourceURL rsURL = res.createResourceURL();
      rsURL.setResourceID(key);
      return rsURL.toString();
    } catch (Exception e) {
      return "";
    }
  }
  
  protected List<String> getNotifications() throws Exception {
    return webNftService == null ? new ArrayList<String>() : webNftService.get(new WebNotificationFilter(currentUser, true), 0, maxItemsInPopover);
  }

  protected List<String> getActions() {
    return Arrays.asList("MarkRead", "RemovePopover", "ResetNumberOnBadge");
  }
  
  protected String getActionUrl(String actionName) throws Exception {
    return event(actionName).replace("javascript:ajaxGet('", "").replace("')", "&" + OBJECTID + "=");
  }

  protected boolean isWebActive() {
    return userSettingService == null ? false : userSettingService.get(currentUser).isChannelActive(WebChannel.ID);
  }
  
  protected String getCometdContextName() {
    return (bayeux == null ? "cometd" : bayeux.getCometdContextName());
  }

  protected int getNumberOfMessage() {
    return webNftService == null ? 0 : webNftService.getNumberOnBadge(currentUser);
  }

  protected boolean hasNotifications() throws Exception {
    if (getNotifications().size() > 0) 
      return true;
    return webNftService == null ? false : webNftService.get(new WebNotificationFilter(currentUser), 0, 1).size() > 0;
  }

  public String getUserToken() {
    try {
      return continuation.getUserToken(currentUser);
    } catch (Exception e) {
      LOG.error("Could not retrieve continuation token for user " + currentUser, e);
      return "";
    }
  }
  
  public static class MarkReadActionListener extends EventListener<UINotificationPopoverToolbarPortlet> {
    public void execute(Event<UINotificationPopoverToolbarPortlet> event) throws Exception {
      String notificationId = event.getRequestContext().getRequestParameter(OBJECTID);
      UINotificationPopoverToolbarPortlet portlet = event.getSource();
      portlet.webNftService.markRead(notificationId);
      // Ignore reload portlet
      ((PortalRequestContext) event.getRequestContext().getParentAppRequestContext()).ignoreAJAXUpdateOnPortlets(true);
    }
  }

  public static class RemovePopoverActionListener extends EventListener<UINotificationPopoverToolbarPortlet> {
    public void execute(Event<UINotificationPopoverToolbarPortlet> event) throws Exception {
      String id = event.getRequestContext().getRequestParameter(OBJECTID);
      UINotificationPopoverToolbarPortlet portlet = event.getSource();
      portlet.webNftService.hidePopover(id);
      // Ignore reload portlet
      ((PortalRequestContext) event.getRequestContext().getParentAppRequestContext()).ignoreAJAXUpdateOnPortlets(true);
    }
  }

  public static class MarkAllReadActionListener extends EventListener<UINotificationPopoverToolbarPortlet> {
    public void execute(Event<UINotificationPopoverToolbarPortlet> event) throws Exception {
      UINotificationPopoverToolbarPortlet portlet = event.getSource();
      portlet.webNftService.markAllRead(portlet.currentUser);
      // Ignore reload portlet
      ((PortalRequestContext) event.getRequestContext().getParentAppRequestContext()).ignoreAJAXUpdateOnPortlets(true);
    }
  }

  public static class ResetNumberOnBadgeActionListener extends EventListener<UINotificationPopoverToolbarPortlet> {
    public void execute(Event<UINotificationPopoverToolbarPortlet> event) throws Exception {
      UINotificationPopoverToolbarPortlet portlet = event.getSource();
      portlet.webNftService.resetNumberOnBadge(portlet.currentUser);
      // Ignore reload portlet
      ((PortalRequestContext) event.getRequestContext().getParentAppRequestContext()).ignoreAJAXUpdateOnPortlets(true);
    }
  }
}
