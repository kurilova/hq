package org.hyperic.tools.ant.dbupgrade;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.tools.ant.BuildException;
import org.hyperic.util.jdbc.DBUtil;
/**
 * Removes old self-monitoring resources representing the HQ JBoss server and its embedded Tomcat server.
 * Since it is possible for one HQ server to be monitoring other HQ servers which have not yet been upgraded,
 * we only remove the resources whose installpath matches the server we are upgrading
 * @author jhickey
 *
 */
public class HQJBossServerRemover
    extends SchemaSpecTask {

    private String upgradeDir;

    private int deletedAlertDefinitions;
    private int removedFromGroups;
    private int removedFromApps;

    private static final String SCHEMA_MOD_IN_PROGRESS = " *** UPGRADE TASK: Removing old HQ self-monitoring servers ";

    public void execute() throws BuildException {
        Connection conn = null;
        Statement stmt1 = null;
        Statement stmt2 = null;
        try {
            conn = getConnection();
            stmt1 = conn.createStatement();
            stmt2 = conn.createStatement();
            log(SCHEMA_MOD_IN_PROGRESS);
            removeServer(stmt1, "HQ JBoss 4.x", upgradeDir + "/hq-engine/server/default");
            removeServer(stmt2, "HQ Tomcat 6.0", upgradeDir +
                                                 "/hq-engine/server/default/deploy/jboss-web.deployer");
        } catch (SQLException e) {
            throw new BuildException(HQJBossServerRemover.class + ": " + e.getMessage(), e);
        } finally {
            DBUtil.closeStatement(HQJBossServerRemover.class, stmt1);
            DBUtil.closeStatement(HQJBossServerRemover.class, stmt2);
        }
    }

    private void removeServer(Statement stmt, String autoinventoryidentifier, String installpath)
        throws SQLException {
        String sql;
        ResultSet rs = null;
        Statement serverUpdateStmt = null;
        try {
            sql = "SELECT id,resource_id FROM EAM_SERVER WHERE autoinventoryidentifier='" +
                  autoinventoryidentifier + "' AND installpath='" + installpath + "'";
            rs = stmt.executeQuery(sql);

            // There should never be more than one server with the same
            // identifier and installpath, but we'll log count just to be sure
            int removedServers = 0;
            while (rs.next()) {
                int serverId = rs.getInt("id");
                int serverResourceId = rs.getInt("resource_id");
                // delete services
                String serviceSql = "SELECT id,resource_id FROM EAM_SERVICE WHERE server_id=" +
                                    serverId;
                Statement serviceQueryStmt = null;
                Statement serviceUpdateStmt = null;
                ResultSet serviceSet = null;
                try {
                    serviceQueryStmt = getConnection().createStatement();
                    serviceUpdateStmt = getConnection().createStatement();
                    serviceSet = serviceQueryStmt.executeQuery(serviceSql);
                    int removedServices = 0;
                    while (serviceSet.next()) {
                        int serviceId = serviceSet.getInt("id");
                        int serviceResourceId = serviceSet.getInt("resource_id");
                        int configResponseId = getConfigResponseId(serviceId, false);
                        deleteService(serviceUpdateStmt, serviceId);
                        removeServerOrServiceStuff(serviceUpdateStmt, serviceId, serviceResourceId,
                            false, configResponseId);
                        removedServices++;
                    }
                    log("Removed " + removedServices + " services from server " +
                        autoinventoryidentifier);
                    log("Removed " + removedFromApps + " of these services from applications");
                    removedFromApps = 0;
                } finally {
                    DBUtil.closeResultSet(HQJBossServerRemover.class, serviceSet);
                    DBUtil.closeStatement(HQJBossServerRemover.class, serviceQueryStmt);
                    DBUtil.closeStatement(HQJBossServerRemover.class, serviceUpdateStmt);
                }
                serverUpdateStmt = getConnection().createStatement();
                int configResponseId = getConfigResponseId(serverId, true);
                deleteServer(serverUpdateStmt, serverId);
                removeServerOrServiceStuff(serverUpdateStmt, serverId, serverResourceId, true,
                    configResponseId);
                removedServers++;
            }
            log("Removed " + deletedAlertDefinitions + " " + autoinventoryidentifier + " alert definitions");
            log("Removed " + removedFromGroups + " " +  autoinventoryidentifier + " resources from groups");
            deletedAlertDefinitions=0;
            removedFromGroups = 0;
            log("Removed " + removedServers + " " + autoinventoryidentifier + " server" +
                ((removedServers > 1) ? "s" : ""));
        } finally {
            DBUtil.closeResultSet(HQJBossServerRemover.class, rs);
            DBUtil.closeStatement(HQJBossServerRemover.class, serverUpdateStmt);
        }

    }

    private void removeServerOrServiceStuff(Statement stmt, int id, int resourceId, boolean server,
                                            int configResponseId) throws SQLException {
        deleteConfigResponse(stmt, configResponseId);
        deleteCProps(stmt, id, server);
        deleteResource(stmt, resourceId);
    }

    private int getConfigResponseId(int serverOrServiceId, boolean server) throws SQLException {
        Statement stmt = null;
        ResultSet rs = null;
        try {
            stmt = getConnection().createStatement();
            String tableName = (server) ? "EAM_SERVER" : "EAM_SERVICE";
            String sql = "SELECT config_response_id FROM " + tableName + " WHERE id =" +
                         serverOrServiceId;
            rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return rs.getInt("config_response_id");
            }
            return -1;
        } finally {
            DBUtil.closeResultSet(HQJBossServerRemover.class, rs);
            DBUtil.closeStatement(HQJBossServerRemover.class, stmt);
        }
    }

    private void deleteConfigResponse(Statement stmt, int configResponseId) throws SQLException {
        String sql = "DELETE FROM EAM_CONFIG_RESPONSE WHERE id =" + configResponseId;
        stmt.executeUpdate(sql);
    }

    private void deleteCProps(Statement stmt, int serverOrServiceId, boolean server)
        throws SQLException {
        int type = (server) ? 2 : 3;
        String sql = "DELETE FROM EAM_CPROP WHERE keyid IN (SELECT id FROM EAM_CPROP_KEY WHERE appdef_type=" +
                     type + ") AND appdef_id=" + serverOrServiceId;
        stmt.executeUpdate(sql);
    }

    private void deleteService(Statement stmt, int serviceId) throws SQLException {
        String sql = "DELETE FROM EAM_APP_SERVICE WHERE service_id=" + serviceId;
        int deleted = stmt.executeUpdate(sql);
        removedFromApps += deleted;

        stmt.executeUpdate("DELETE FROM EAM_SERVICE WHERE id=" + serviceId);
    }

    private void deleteServer(Statement stmt, int serverId) throws SQLException {
        String sql = "DELETE FROM EAM_SERVER WHERE id=" + serverId;
        stmt.executeUpdate(sql);
    }

    private void deleteResource(Statement stmt, int resourceId) throws SQLException {
        stmt.executeUpdate("DELETE FROM EAM_MEASUREMENT" + " WHERE resource_id =" + resourceId);

        stmt.executeUpdate("DELETE FROM EAM_VIRTUAL" + " WHERE resource_id =" + resourceId);

        stmt.executeUpdate("DELETE FROM EAM_RESOURCE_EDGE" + " WHERE to_id =" + resourceId);

        stmt.executeUpdate("DELETE FROM EAM_RESOURCE_EDGE" + " WHERE from_id =" + resourceId);

        int removed = stmt.executeUpdate("DELETE FROM EAM_RES_GRP_RES_MAP" +
                                         " WHERE resource_id =" + resourceId);
        removedFromGroups += removed;

        stmt.executeUpdate("UPDATE EAM_AUDIT SET resource_id = 0, original = " +
                           DBUtil.getBooleanValue(false, getConnection()) + " WHERE resource_id=" +
                           resourceId);

        stmt.executeUpdate("DELETE FROM EAM_EVENT_LOG" + " WHERE resource_id =" + resourceId);
        
        stmt.executeUpdate("DELETE FROM EAM_ALERT WHERE alert_definition_id in (SELECT id FROM EAM_ALERT_DEFINITION" +
                                         " WHERE resource_id =" + resourceId + ")");

        int defsDeleted = stmt.executeUpdate("DELETE FROM EAM_ALERT_DEFINITION" +
                                         " WHERE resource_id =" + resourceId);
        deletedAlertDefinitions += defsDeleted;

        stmt.executeUpdate("DELETE from EAM_RESOURCE WHERE id = " + resourceId);
    }

    /**
     * Setter for testing
     * @param connection The connection to use when testing
     */
    void setConnection(Connection connection) {
        _conn = connection;
    }

    public void setUpgradeDir(String upgradeDir) {
        log("Setting upgradeDir to " + upgradeDir);
        this.upgradeDir = upgradeDir;
    }

}
