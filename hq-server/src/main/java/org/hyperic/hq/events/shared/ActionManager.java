/*
 * Generated by XDoclet - Do not edit!
 */
package org.hyperic.hq.events.shared;

import java.util.List;

import org.hyperic.hq.events.ActionConfigInterface;
import org.hyperic.hq.events.server.session.Action;
import org.hyperic.hq.events.server.session.AlertDefinition;
import org.hyperic.util.config.ConfigResponse;
import org.hyperic.util.config.EncodingException;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Local interface for ActionManager.
 */
public interface ActionManager {
    /**
     * Get all the actions for a given alert
     * @return a collection of {@link ActionValue}s
     */
    public List<ActionValue> getActionsForAlert(int alertId);

    /**
     * Create a new action
     */
    public Action createAction(AlertDefinition def, String className, ConfigResponse config, Action parent)
        throws EncodingException;

    /**
     * Update an action
     */
    public Action updateAction(ActionValue val);

    /**
     * Create a free-standing action. These are linked to from things like
     * escalations actions. XXX: This should really be removed -- the JSON
     * object sucks.
     */
    public Action createAction(JSONObject json) throws JSONException;

    /**
     * Create a free-standing action. These are linked to from things like
     * escalations actions.
     */
    public Action createAction(ActionConfigInterface cfg);

    /**
     * Mark a free-standing action as deleted. These actions will later be
     * deleted by a cleanup thread.
     */
    public void markActionDeleted(Action a);

}