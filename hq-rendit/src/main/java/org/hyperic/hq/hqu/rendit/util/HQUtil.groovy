package org.hyperic.hq.hqu.rendit.util


import org.hyperic.hq.authz.shared.AuthzSubjectManager;
import org.hyperic.hq.common.shared.ServerConfigManager;
import org.hyperic.hq.authz.server.session.AuthzSubject
import org.hyperic.hq.common.shared.HQConstants
import org.hyperic.hq.common.server.session.ServerConfigManagerImpl
import org.hyperic.hq.context.Bootstrap;

class HQUtil {
    private static final Object LOCK = new Object()
    private static String  BASE_URL
    private static Boolean IS_EE = null

    /**
     * Get the base URL which can be used to contact HQ
     */
    static String getBaseURL() {
        synchronized (LOCK) { 
            if (BASE_URL == null) {
                BASE_URL = Bootstrap.getBean(ServerConfigManager).
                                 getConfig().getProperty(HQConstants.BaseURL)
                if (BASE_URL[-1] == '/')
                    BASE_URL = BASE_URL[0..-2]
            }
        }
        BASE_URL
    }
    
    static boolean isEnterpriseEdition() {
        synchronized (LOCK) {
            if (IS_EE != null) 
                return IS_EE
                
            try {
                Class.forName("com.hyperic.hq.authz.shared.PermissionManagerImpl")
                IS_EE = true
            } catch(Exception e) {
                IS_EE = false
            }
            
            return IS_EE
        }
    }
    
    static AuthzSubject getOverlord() {
        Bootstrap.getBean(AuthzSubjectManager.class).overlordPojo
    }    
}