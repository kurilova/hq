/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2004-2008], Hyperic, Inc.
 * This file is part of HQ.
 *
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.product.server.session;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import javax.ejb.CreateException;
import javax.ejb.FinderException;
import javax.ejb.RemoveException;
import javax.ejb.SessionBean;
import javax.ejb.SessionContext;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.dao.DAOFactory;
import org.hyperic.hq.alerts.AlertDefinitionXmlParser;
import org.hyperic.hq.appdef.server.session.AppdefResourceType;
import org.hyperic.hq.appdef.server.session.CPropManagerEJBImpl;
import org.hyperic.hq.appdef.server.session.ConfigManagerEJBImpl;
import org.hyperic.hq.appdef.server.session.CpropKey;
import org.hyperic.hq.appdef.shared.AppdefEntityID;
import org.hyperic.hq.appdef.shared.AppdefEntityNotFoundException;
import org.hyperic.hq.appdef.shared.AppdefEntityValue;
import org.hyperic.hq.appdef.shared.CPropManagerLocal;
import org.hyperic.hq.appdef.shared.ConfigManagerLocal;
import org.hyperic.hq.authz.shared.PermissionException;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.common.VetoException;
import org.hyperic.hq.common.server.session.Audit;
import org.hyperic.hq.common.server.session.AuditManagerEJBImpl;
import org.hyperic.hq.context.Bootstrap;
import org.hyperic.hq.events.EventConstants;
import org.hyperic.hq.events.server.session.AlertDefinitionManagerEJBImpl;
import org.hyperic.hq.events.shared.AlertDefinitionManagerLocal;
import org.hyperic.hq.events.shared.AlertDefinitionValue;
import org.hyperic.hq.measurement.server.session.MonitorableType;
import org.hyperic.hq.measurement.server.session.TemplateManagerEJBImpl;
import org.hyperic.hq.measurement.shared.TemplateManagerLocal;
import org.hyperic.hq.product.MeasurementInfo;
import org.hyperic.hq.product.Plugin;
import org.hyperic.hq.product.PluginException;
import org.hyperic.hq.product.PluginInfo;
import org.hyperic.hq.product.PluginManager;
import org.hyperic.hq.product.PluginNotFoundException;
import org.hyperic.hq.product.PluginUpdater;
import org.hyperic.hq.product.ProductPlugin;
import org.hyperic.hq.product.ProductPluginManager;
import org.hyperic.hq.product.ServerTypeInfo;
import org.hyperic.hq.product.ServiceType;
import org.hyperic.hq.product.TypeInfo;
import org.hyperic.hq.product.pluginxml.PluginData;
import org.hyperic.hq.product.server.MBeanUtil;
import org.hyperic.hq.product.shared.PluginValue;
import org.hyperic.hq.product.shared.ProductManagerLocal;
import org.hyperic.hq.product.shared.ProductManagerUtil;
import org.hyperic.util.config.ConfigOption;
import org.hyperic.util.config.ConfigResponse;
import org.hyperic.util.config.ConfigSchema;
import org.hyperic.util.timer.StopWatch;

/**
 * @ejb:bean name="ProductManager"
 *           jndi-name="ejb/product/ProductManager"
 *           local-jndi-name="LocalProductManager"
 *           view-type="local"
 *           type="Stateless"
 */
public class ProductManagerEJBImpl 
    implements SessionBean 
{
	
	//XXX constant should be elsewhere
    private final String PLUGIN_DEPLOYER = 
        "hyperic.jmx:type=Service,name=ProductPluginDeployer";

    private Log log = LogFactory.getLog(ProductManagerEJBImpl.class);

    private ProductPluginManager   ppm;
    private ConfigManagerLocal     configManagerLocal;
    private CPropManagerLocal      cPropManagerLocal;
    private TemplateManagerLocal   templateManagerLocal;
    private PluginUpdater pluginUpdater = new PluginUpdater();
    private static final String ALERT_DEFINITIONS_XML_FILE = "etc/alert-definitions.xml";
    private AlertDefinitionManagerLocal alertDefinitionManagerLocal;
    private PluginDAO pluginDao = Bootstrap.getBean(PluginDAO.class);
    

    /*
     * There is once instance of the ProductPluginDeployer service
     * MBean, it will be deployed before we are created.
     */
    private ProductPluginManager getProductPluginManager()
        throws PluginException {

        MBeanServer server = MBeanUtil.getMBeanServer();
        ObjectName deployer;

        try {
            deployer = new ObjectName(PLUGIN_DEPLOYER);
        } catch (MalformedObjectNameException e) {
            //wont happen.
            throw new PluginException(e.getMessage(), e);
        }

        try {
            return (ProductPluginManager)
                server.getAttribute(deployer, "ProductPluginManager");
        } catch (MBeanException e) {
            throw new PluginException(e.getMessage(), e);
        } catch (AttributeNotFoundException e) {
            throw new PluginException(e.getMessage(), e);
        } catch (InstanceNotFoundException e) {
            throw new PluginException(e.getMessage(), e);
        } catch (ReflectionException e) {
            throw new PluginException(e.getMessage(), e);
        }
    }

    public ProductManagerEJBImpl(){
        try {
            ppm = getProductPluginManager();
        } catch (PluginException e) {
            log.error("Unable to initialize plugin manager: " +
                           e.getMessage());
        }
    }

    public static ProductManagerLocal getOne() {
        try {
            return ProductManagerUtil.getLocalHome().create();
        } catch(Exception e) {
            throw new SystemException(e);
        }
    }

    public void ejbCreate() {}
    public void ejbRemove() {}
    public void ejbActivate() {}
    public void ejbPassivate() {}
    public void setSessionContext(SessionContext ctx) {}

    /**
     * @ejb:interface-method
     */
    public boolean isReady() {
        MBeanServer server = MBeanUtil.getMBeanServer();
        ObjectName deployer;

        try {
            deployer = new ObjectName(PLUGIN_DEPLOYER);
        } catch (MalformedObjectNameException e) {
            //wont happen.
            throw new SystemException(e.getMessage(), e);
        }

        try {
            return ((Boolean)server.getAttribute(deployer, "Ready")).booleanValue();
        } catch (Exception e) {
            log.error("Unable to determine deployer state", e);
            return false;
        }
    }

    /**
     * @ejb:interface-method
     */
    public TypeInfo getTypeInfo(AppdefEntityValue value)
        throws PermissionException, AppdefEntityNotFoundException
    {
        return ppm.getTypeInfo(value.getBasePlatformName(),
                                    value.getTypeName());
    }

    /**
     * @ejb:interface-method
     */
    public PluginManager getPluginManager(String type)
        throws PluginException
    {
        return ppm.getPluginManager(type);
    }

    /**
     * @ejb:interface-method
     */
    public String getMonitoringHelp(AppdefEntityValue entityVal, Map props)
        throws PluginNotFoundException, PermissionException,
               AppdefEntityNotFoundException
    {
        TypeInfo info = getTypeInfo(entityVal);
        String help =
            ppm.getMeasurementPluginManager().getHelp(info, props);
        if (help == null) {
            return null;
        }
        return help;
    }

    /**
     * @ejb:interface-method
     */
    public ConfigSchema getConfigSchema(String type,
                                        String name,
                                        AppdefEntityValue entityVal,
                                        ConfigResponse baseResponse)
        throws PluginException,
               AppdefEntityNotFoundException,
               PermissionException {

        PluginManager manager = getPluginManager(type);
        TypeInfo info = getTypeInfo(entityVal);
        return manager.getConfigSchema(name, info, baseResponse);
    }

    private void updateEJBPlugin(PluginDAO plHome, PluginInfo pInfo)
    {
        Plugin ejbPlugin = plHome.findByName(pInfo.name);
        if (ejbPlugin == null) {
            plHome.create(pInfo.name, pInfo.jar, pInfo.md5);
        } else {
            ejbPlugin.setPath(pInfo.jar);
            ejbPlugin.setMD5(pInfo.md5);
        }
    }

    //e.g. in ~/.hq/plugin.properties
    //hq.plugins.system.forceUpdate=true
    private boolean forceUpdate(String plugin) {
        String key =
            ProductPluginManager.getPropertyKey(plugin, "forceUpdate");

        return "true".equals(ppm.getProperties().getProperty(key));
    }

    private void pluginDeployed(PluginInfo pInfo) {
        //there is 1 hq-plugin.xml descriptor per-plugin which
        //contains metrics for all types supported by said plugin.
        //caching prevents reading/parsing the file for each type.
        //at this point we've got all the measurements for this plugin
        //so flush the cache to save some memory.
        //the file will be re-read/parsed when the plugin is redeployed.
        PluginData.deployed(pInfo.resourceLoader);
    }

    private boolean isVirtualServer(TypeInfo type) {
        if (type.getType() != TypeInfo.TYPE_SERVER) {
            return false;
        }
        return ((ServerTypeInfo)type).isVirtual();
    }

    /**
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public void deploymentNotify(String pluginName)
        throws PluginNotFoundException, FinderException,
               CreateException, RemoveException, VetoException
    {
        ProductPlugin pplugin = (ProductPlugin) ppm.getPlugin(pluginName);
        //PluginDAO pluginDao = pluginDao;
        PluginValue ejbPlugin;
        PluginInfo pInfo;
        boolean created = false;
        long start = System.currentTimeMillis();

        pInfo = ppm.getPluginInfo(pluginName);
        Plugin plugin = pluginDao.findByName(pluginName);
        ejbPlugin = plugin != null ?plugin.getPluginValue() : null;

        if(ejbPlugin != null &&
           pInfo.name.equals(ejbPlugin.getName()) &&
           pInfo.md5.equals(ejbPlugin.getMD5()))
        {
            log.info(pluginName + " plugin up to date");
            if (forceUpdate(pluginName)) {
                log.info(pluginName + " configured to force update");
            }
            else {
                pluginDeployed(pInfo);
                return;
            }
        } else {
            log.info(pluginName + " unknown -- registering");
            created = (ejbPlugin == null);
        }
           
        // Get the Appdef entities
        TypeInfo[] entities = pplugin.getTypes();
        if (entities == null) {
            log.info(pluginName + " does not define any resource types");
            updateEJBPlugin(pluginDao, pInfo);
            if (created)
                PluginAudit.deployAudit(pluginName, start, 
                                        System.currentTimeMillis());
            else
                PluginAudit.updateAudit(pluginName, start, 
                                        System.currentTimeMillis());
            return;
        }

        Audit audit; 
        boolean pushed = false;
        
        if (created)
            audit = PluginAudit.deployAudit(pluginName, start, start);
        else
            audit = PluginAudit.updateAudit(pluginName, start, start);
        
        try {
            AuditManagerEJBImpl.getOne().pushContainer(audit);
            pushed = true;
            updatePlugin(pluginName);   
        } finally {
            if (pushed) {
                AuditManagerEJBImpl.getOne().popContainer(true);
            }
        }
    }
    
    /**
     * @param pluginName The name of the product plugin
     * @param serviceTypes The Set of {@link ServiceType}s to update
     * @throws PluginNotFoundException 
     * @throws VetoException
     * @throws CreateException 
     * @throws RemoveException 
     * @throws FinderException 
     * @ejb:interface-method
     * @ejb:transaction type="Required"
     */
    public void updateDynamicServiceTypePlugin(String pluginName, Set serviceTypes) throws PluginNotFoundException, FinderException, RemoveException, CreateException, VetoException {
    	ProductPlugin productPlugin = (ProductPlugin) ppm.getPlugin(pluginName);
    	try {
			pluginUpdater.updateServiceTypes(productPlugin, serviceTypes);
			updatePlugin(pluginName);
		}
		catch (PluginException e) {
			log.error("Error updating service types.  Cause: " + e.getMessage());
		}
    }
    
   
    private void updatePlugin(String pluginName) throws FinderException, RemoveException, CreateException, VetoException, PluginNotFoundException {
    	ProductPlugin pplugin = (ProductPlugin) ppm.getPlugin(pluginName);
    	
    	PluginInfo pInfo = ppm.getPluginInfo(pluginName);
    	
    	
    	TypeInfo[] entities = pplugin.getTypes();
    	getConfigManagerLocal().updateAppdefEntities(pluginName, entities);

        StopWatch timer = new StopWatch();

         // Get the measurement templates
         TemplateManagerLocal tMan = getTemplateManagerLocal();
         // Keep a list of templates to add
         HashMap toAdd = new HashMap();

         for (int i = 0; i < entities.length; i++) {
             TypeInfo info = entities[i];
         
             MeasurementInfo[] measurements;

             try {
                 measurements =
                     ppm.getMeasurementPluginManager().getMeasurements(info);
             } catch (PluginNotFoundException e) {
                 if (!isVirtualServer(info)) {
                     log.info(info.getName() +
                                   " does not support measurement");
                 }
                 continue;
             }

             if (measurements != null && measurements.length > 0) {
                 MonitorableType monitorableType =
                     tMan.getMonitorableType(pluginName, info);
                 Map newMeasurements = tMan.updateTemplates(pluginName, info,
                                                            monitorableType,
                                                            measurements);
                 toAdd.put(monitorableType, newMeasurements);
             }
         }

         // For performance reasons, we add all the new measurements at once.
         tMan.createTemplates(pluginName, toAdd);

         // Add any custom properties.
         CPropManagerLocal cPropManager = getCPropManagerLocal();
         for (int i = 0; i < entities.length; i++) {
             TypeInfo info = entities[i];
             ConfigSchema schema = pplugin.getCustomPropertiesSchema(info);
             List options = schema.getOptions();
             AppdefResourceType appdefType = cPropManager.findResourceType(info);
             for (Iterator j=options.iterator(); j.hasNext();) {
                 ConfigOption opt = (ConfigOption)j.next();
                 CpropKey c = cPropManager.findByKey(appdefType, opt.getName());
                 if (c == null) {
                     cPropManager.addKey(appdefType, opt.getName(),
                                         opt.getDescription());
                 }
             }
         }
         createAlertDefinitions(pInfo);
         pluginDeployed(pInfo);
         updateEJBPlugin(pluginDao, pInfo);
    }
    
    private void createAlertDefinitions(final PluginInfo pInfo) throws FinderException, RemoveException, CreateException, VetoException {
        final InputStream alertDefns = pInfo.resourceLoader.getResourceAsStream(ALERT_DEFINITIONS_XML_FILE);
        if(alertDefns == null) {
                   return;
        }
        //TODO DI
        AlertDefinitionXmlParser alertDefXmlParser   = new AlertDefinitionXmlParser();
       try {
           final Set alertDefs = alertDefXmlParser.parse(alertDefns);
           for(Iterator iterator=alertDefs.iterator();iterator.hasNext();) {
               try {
                   final AlertDefinitionValue alertDefinition =  (AlertDefinitionValue)iterator.next();
                   final AppdefEntityID id = new AppdefEntityID(alertDefinition.getAppdefType(),alertDefinition.getAppdefId());
                   final SortedMap existingAlertDefinitions = getAlertDefinitionManagerLocal().findAlertDefinitionNames(id, EventConstants.TYPE_ALERT_DEF_ID);
                   //TODO update existing alert defs - for now, just create if one does not exist.  Be aware that this method is also called
                   //when new service type metadata is discovered (from updateServiceTypes method), as well as when a new or modified plugin jar is detected
                   if(!(existingAlertDefinitions.keySet().contains(alertDefinition.getName()))) {
                       getAlertDefinitionManagerLocal().createAlertDefinition(alertDefinition);
                   }
               } catch (Exception e) {
                   log.error("Unable to load some or all of alert definitions for plugin " + pInfo.name + ".  Cause: " + e.getMessage());
               } 
           }
       } catch (Exception e) {
           log.error("Unable to parse alert definitions for plugin " + pInfo.name + ".  Cause: " + e.getMessage());
           }finally {
               try {
                   alertDefns.close();
               } catch (IOException e) {
                   log.warn("Error closing InputStream to alert definitions file of plugin " + pInfo.name + 
                            ".  Cause: " + e.getMessage());
               }
           }
     }
    
    private AlertDefinitionManagerLocal getAlertDefinitionManagerLocal() {
        if(alertDefinitionManagerLocal == null)
            alertDefinitionManagerLocal = AlertDefinitionManagerEJBImpl.getOne();
        return alertDefinitionManagerLocal;
    }

    private ConfigManagerLocal getConfigManagerLocal() {
        if(configManagerLocal == null)
            configManagerLocal = ConfigManagerEJBImpl.getOne();
        return configManagerLocal;
    }
    

    private CPropManagerLocal getCPropManagerLocal() {
        if(cPropManagerLocal == null)
            cPropManagerLocal = CPropManagerEJBImpl.getOne();
        return cPropManagerLocal;
    }

    private TemplateManagerLocal getTemplateManagerLocal() {
        if(templateManagerLocal == null)
            templateManagerLocal = TemplateManagerEJBImpl.getOne();
        return templateManagerLocal;
    }

   
}
