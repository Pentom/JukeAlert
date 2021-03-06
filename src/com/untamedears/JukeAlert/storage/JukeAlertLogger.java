/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.untamedears.JukeAlert.storage;

import com.untamedears.JukeAlert.JukeAlert;
import com.untamedears.JukeAlert.chat.ChatFiller;
import com.untamedears.JukeAlert.group.GroupMediator;
import com.untamedears.JukeAlert.manager.ConfigManager;
import com.untamedears.JukeAlert.model.LoggedAction;
import com.untamedears.JukeAlert.model.Snitch;
import com.untamedears.JukeAlert.tasks.GetSnitchInfoTask;
import com.untamedears.citadel.entity.Faction;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 *
 * @author Dylan Holmes
 */
public class JukeAlertLogger {

    private final ConfigManager configManager;
    private final Database db;
    private final String snitchsTbl;
    private final String snitchDetailsTbl;
    private PreparedStatement getSnitchIdFromLocationStmt;
    private PreparedStatement getAllSnitchesStmt;
    private PreparedStatement getLastSnitchID;
    private PreparedStatement getSnitchLogStmt;
    private PreparedStatement getSnitchLogGroupStmt;
    private PreparedStatement getSnitchListStmt;
    private PreparedStatement deleteSnitchLogStmt;
    private PreparedStatement insertSnitchLogStmt;
    private PreparedStatement insertNewSnitchStmt;
    private PreparedStatement deleteSnitchStmt;
    private PreparedStatement updateGroupStmt;
    private PreparedStatement updateCuboidVolumeStmt;
    private PreparedStatement updateSnitchNameStmt;
    private PreparedStatement updateSnitchToggleLeversStmt;
    private PreparedStatement updateSnitchGroupStmt;
    private PreparedStatement getAllSnitchIdsStmt;
    private PreparedStatement cullSnitchEntriesStmt;
    private PreparedStatement cullSnitchEntriesAndSnitchesBasedOnVisitDateStmt;
    private final int logsPerPage;
    private int lastSnitchID;
    private final int maxEntryCount;
    private final int minEntryLifetimeDays;
    private final int maxEntryLifetimeDays;
    private final int daysFromLastAdminVisitForLoggedSnitchCulling;
    private final int daysFromLastAdminVisitForNonLoggedSnitchCulling;
    private final String host;
    private final String dbname;
    private final String username;
    // The following are used by SnitchEnumerator
    protected JukeAlert plugin;
    protected GroupMediator groupMediator;
    protected PreparedStatement getAllSnitchesByWorldStmt;
	private PreparedStatement getAllSnitchesByGroupStmt;

    public JukeAlertLogger() {
        plugin = JukeAlert.getInstance();
        configManager = plugin.getConfigManager();
        groupMediator = plugin.getGroupMediator();

        host = configManager.getHost();
        int port = configManager.getPort();
        dbname = configManager.getDatabase();
        username = configManager.getUsername();
        String password = configManager.getPassword();
        String prefix = configManager.getPrefix();
        maxEntryCount = configManager.getMaxSnitchEntryCount();
        minEntryLifetimeDays = configManager.getMinSnitchEntryLifetime();
        maxEntryLifetimeDays = configManager.getMaxSnitchEntryLifetime();
        
        daysFromLastAdminVisitForLoggedSnitchCulling = configManager.getDaysFromLastAdminVisitForLoggedSnitchCulling();
        daysFromLastAdminVisitForNonLoggedSnitchCulling = configManager.getDaysFromLastAdminVisitForNonLoggedSnitchCulling();

        logsPerPage = configManager.getLogsPerPage();
        snitchsTbl = prefix + "snitchs";
        snitchDetailsTbl = prefix + "snitch_details";

        db = new Database(host, port, dbname, username, password, prefix, this.plugin.getLogger());
        boolean connected = db.connect();
        if (connected) {
            genTables();
            initializeStatements();
            initializeLastSnitchId();
        } else {
            this.plugin.getLogger().log(Level.SEVERE, "Could not connect to the database! Fill out your config.yml!");
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                saveAllSnitches();
            }
        }, 20, 20); // 1 sec
    }

    public Database getDb() {
        return db;
    }

    /**
     * Table generator
     */
    private void genTables() {
        //Snitches
        db.execute("CREATE TABLE IF NOT EXISTS `" + snitchsTbl + "` ("
                + "`snitch_id` int(10) unsigned NOT NULL AUTO_INCREMENT,"
                + "`snitch_world` varchar(40) NOT NULL,"
                + "`snitch_name` varchar(40) NOT NULL,"
                + "`snitch_x` int(10) NOT NULL,"
                + "`snitch_y` int(10) NOT NULL,"
                + "`snitch_z` int(10) NOT NULL,"
                + "`snitch_group` varchar(40) NOT NULL,"
                + "`snitch_cuboid_x` int(10) NOT NULL,"
                + "`snitch_cuboid_y` int(10) NOT NULL,"
                + "`snitch_cuboid_z` int(10) NOT NULL,"
                + "`snitch_should_log` BOOL,"
                + "PRIMARY KEY (`snitch_id`),"
                + "INDEX `idx_y` (`snitch_y` ASC));");
        //Snitch Details
        // need to know:
        // action: (killed, block break, block place, etc), can't be null
        // person who initiated the action (player name), can't be null
        // victim of action (player name, entity), can be null
        // x, (for things like block place, bucket empty, etc, NOT the snitch x,y,z) can be null
        // y, can be null
        // z, can be null
        // block_id, can be null (block id for block place, block use, block break, etc)
        db.execute("CREATE TABLE IF NOT EXISTS `" + snitchDetailsTbl + "` ("
                + "`snitch_details_id` int(10) unsigned NOT NULL AUTO_INCREMENT,"
                + "`snitch_id` int(10) unsigned NOT NULL," // reference to the column in the main snitches table
                + "`snitch_log_time` datetime,"
                + "`snitch_logged_action` tinyint unsigned NOT NULL,"
                + "`snitch_logged_initiated_user` varchar(16) NOT NULL,"
                + "`snitch_logged_victim_user` varchar(16), "
                + "`snitch_logged_x` int(10), "
                + "`snitch_logged_Y` int(10), "
                + "`snitch_logged_z` int(10), "
                + "`snitch_logged_materialid` smallint unsigned," // can be either a block, item, etc
                + "PRIMARY KEY (`snitch_details_id`),"
                + "INDEX `idx_snitch_id` (`snitch_id` ASC),"
                + "CONSTRAINT `fk_snitchs_snitch_id` FOREIGN KEY (`snitch_id`)"
                + "  REFERENCES `" + snitchsTbl + "` (`snitch_id`) ON DELETE CASCADE ON UPDATE CASCADE);");

        db.silentExecute(String.format(
            "ALTER TABLE %s ADD INDEX idx_log_time (snitch_log_time ASC);", snitchDetailsTbl));
        
        db.silentExecute(String.format("ALTER TABLE %s ADD COLUMN (last_semi_owner_visit_date DATETIME, INDEX idx_last_visit(last_semi_owner_visit_date, snitch_should_log));", snitchsTbl));
        db.silentExecute(String.format("UPDATE %s SET last_semi_owner_visit_date = UTC_TIMESTAMP() WHERE last_semi_owner_visit_date IS NULL;", snitchsTbl));
        db.silentExecute(String.format("ALTER TABLE %s MODIFY COLUMN last_semi_owner_visit_date DATETIME NOT NULL;", snitchsTbl));

        db.silentExecute(String.format("ALTER TABLE %s ADD COLUMN (allow_triggering_lever bit);", snitchsTbl));
        db.silentExecute(String.format("UPDATE %s SET allow_triggering_lever = 0 WHERE allow_triggering_lever IS NULL;", snitchsTbl));
        db.silentExecute(String.format("ALTER TABLE %s MODIFY COLUMN allow_triggering_lever bit NOT NULL;", snitchsTbl));
        
        try {

            this.plugin.getLogger().log(Level.INFO, "Adding the log_hour column");
            db.executeLoud(MessageFormat.format(
                "ALTER TABLE {0} ADD COLUMN (log_hour MEDIUMINT, INDEX idx_log_hour (log_hour));",
                snitchDetailsTbl));

            this.plugin.getLogger().log(Level.INFO, "Populating the log_hour column");
            db.executeLoud(MessageFormat.format(
                "UPDATE {0} SET log_hour = TIMESTAMPDIFF(HOUR, ''2013-01-01 00:00:00'', snitch_log_time);",
                snitchDetailsTbl));
        	
            db.executeLoud(MessageFormat.format(
                " CREATE DEFINER=CURRENT_USER PROCEDURE CullSnitches( "
                + " IN minDays INT, IN maxDays INT, IN maxEntries INT) SQL SECURITY INVOKER BEGIN\n"
                + " DECLARE done BOOLEAN DEFAULT FALSE;\n"
                + " DECLARE snId, today, minHour, maxHour INT;\n"
                + " DECLARE snCur CURSOR FOR SELECT {0}.snitch_id FROM {0}\n"
                + "   INNER JOIN {1} on {0}.snitch_id = {1}.snitch_id\n"
                + "   GROUP BY {0}.snitch_id HAVING COUNT(*) > maxEntries;\n"
                + " DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;\n"
                + " SET today = TIMESTAMPDIFF(HOUR, ''2013-01-01 00:00:00'', CURRENT_TIMESTAMP());\n"
                + " SET minHour = TIMESTAMPDIFF(HOUR, ''2013-01-01 00:00:00'', DATE_SUB(CURRENT_TIMESTAMP(), INTERVAL minDays DAY));\n"
                + " SET maxHour = TIMESTAMPDIFF(HOUR, ''2013-01-01 00:00:00'', DATE_SUB(CURRENT_TIMESTAMP(), INTERVAL maxDays DAY));\n"
                + " DELETE FROM {1} WHERE log_hour < maxHour;\n"
                + " OPEN snCur;\n"
                + " da_loop: LOOP\n"
                + " FETCH snCur INTO snId;\n"
                + " IF done THEN CLOSE snCur; LEAVE da_loop; END IF;\n"
                + " DELETE FROM {1} WHERE snitch_id = snId AND log_hour < minHour AND snitch_details_id NOT IN ((\n"
                + "   SELECT snitch_details_id FROM (SELECT snitch_details_id FROM {1}\n"
                + "     WHERE snitch_id = snId ORDER BY snitch_log_time DESC LIMIT maxEntries) AS drvTbl));\n"
                + " END LOOP da_loop; END", snitchsTbl, snitchDetailsTbl));

            this.plugin.getLogger().log(Level.INFO, "Creating the log_hour trigger");
            db.executeLoud(MessageFormat.format(
                "CREATE TRIGGER trig_log_hour BEFORE INSERT ON {0} FOR EACH ROW "
                + " SET NEW.log_hour = TIMESTAMPDIFF(HOUR, ''2013-01-01 00:00:00'', NEW.snitch_log_time);",
                snitchDetailsTbl));
            this.plugin.getLogger().log(Level.INFO, "log_hour setup in the DB");
        } catch (Exception ex) {
            String exMsg = ex.toString();
            if (!exMsg.contains("multiple triggers with the same action time and event for one table")
                    && !exMsg.contains("PROCEDURE CullSnitches already exists")
                    && !exMsg.contains("Duplicate column name")) {
                this.plugin.getLogger().log(Level.SEVERE, exMsg);
            }
        }

        try {
            db.executeLoud(MessageFormat.format(
            		" CREATE DEFINER=CURRENT_USER PROCEDURE CullSnitchesBasedOnLastVisitDate(                                                                                                "
            				+ " 	IN DaysFromLastAdminVisitForLoggedSnitchCulling INT, IN DaysFromLastAdminVisitForNonLoggedSnitchCulling INT) SQL SECURITY INVOKER BEGIN                          \n"
            				+ "                                                                                                                                                                      \n"
            				+ " 	DECLARE done BOOLEAN DEFAULT FALSE;                                                                                                                              \n"
            				+ " 	DECLARE SnitchId INT;                                                                                                                                            \n"
            				+ " 	                                                                                                                                                                 \n"
            				+ " 	DECLARE snCursor CURSOR FOR                                                                                                                                      \n"
            				+ " 	SELECT snitch_id                                                                                                                                                 \n"
            				+ " 	FROM                                                                                                                                                             \n"
            				+ " 		{0} s                                                                                                                                                    \n"
            				+ " 	WHERE                                                                                                                                                            \n"
            				+ " 		(                                                                                                                                                            \n"
            				+ " 			(snitch_should_log = 1 AND DaysFromLastAdminVisitForLoggedSnitchCulling >= 1)                                                                            \n"
            				+ " 			AND                                                                                                                                                      \n"
            				+ " 			(TIMESTAMPDIFF(SECOND, DATE_ADD(UTC_TIMESTAMP(), INTERVAL -DaysFromLastAdminVisitForLoggedSnitchCulling DAY) ,s.last_semi_owner_visit_date) <= 0)        \n"
            				+ " 		)                                                                                                                                                            \n"
            				+ " 		OR                                                                                                                                                           \n"
            				+ " 		(                                                                                                                                                            \n"
            				+ " 			(snitch_should_log = 0 AND DaysFromLastAdminVisitForNonLoggedSnitchCulling >= 1)                                                                         \n"
            				+ " 			AND                                                                                                                                                      \n"
            				+ " 			(TIMESTAMPDIFF(SECOND, DATE_ADD(UTC_TIMESTAMP(), INTERVAL -DaysFromLastAdminVisitForNonLoggedSnitchCulling DAY) ,s.last_semi_owner_visit_date) <= 0)     \n"
            				+ " 		);                                                                                                                                                           \n"
            				+ " 		                                                                                                                                                             \n"
            				+ " 	DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;                                                                                                          \n"
            				+ " 	                                                                                                                                                                 \n"
            				+ " 	OPEN snCursor;                                                                                                                                                   \n"
            				+ " 	da_loop: LOOP                                                                                                                                                    \n"
            				+ " 		                                                                                                                                                             \n"
            				+ " 		FETCH snCursor INTO SnitchId;                                                                                                                                \n"
            				+ " 		IF done THEN CLOSE snCursor; LEAVE da_loop;  END IF;                                                                                                         \n"
            				+ " 		                                                                                                                                                             \n"
            				+ " 		DELETE FROM {1} WHERE snitch_id = SnitchId;                                                                                                       \n"
            				+ " 		DELETE FROM {0} WHERE snitch_id = SnitchId;                                                                                                              \n"
            				+ " 		                                                                                                                                                             \n"
            				+ " 		                                                                                                                                                             \n"
            				+ " 	END LOOP da_loop;                                                                                                                                                \n"
            				+ " 		                                                                                                                                                             \n"
            				+ "                                                                                                                                                                      \n"
            				+ " END                                                                                                                                                                  "
            				, snitchsTbl, snitchDetailsTbl));

        } catch (Exception ex) {
            String exMsg = ex.toString();
            if (!exMsg.contains("PROCEDURE CullSnitchesBasedOnLastVisitDate already exists")) {
                this.plugin.getLogger().log(Level.SEVERE, exMsg);
            }
        }
        db.silentExecute(String.format("ALTER TABLE %s MODIFY COLUMN `snitch_group` varchar(255) NOT NULL;", snitchsTbl));

        // Let's get some DB versioning in the next plugin, ok guys?

        try {
        	db.silentExecute("DROP PROCEDURE GetJukeboxListForDelimitedGroup;");
            db.executeLoud(MessageFormat.format(
            		" CREATE DEFINER=CURRENT_USER PROCEDURE GetJukeboxListForDelimitedGroup(                                         \n"
            				+ " 	IN ValueString LONGTEXT                                                                                    \n"
            				+ " 	, IN DELIM VARCHAR(255)                                                                                    \n"
            				+ " 	, IN daysFromLastAdminVisitForLoggedSnitchCulling INT                                                      \n"
            				+ " 	, IN daysFromLastAdminVisitForNonLoggedSnitchCulling INT                                                   \n"
            				+ " 	, IN skipRecordCount INT                                                                                   \n"
            				+ " 	, IN itemsOnPage INT                                                                                       \n"
            				+ " ) SQL SECURITY INVOKER BEGIN                                                                                   \n"
            				+ "                                                                                                                \n"
            				+ " 	DROP TEMPORARY TABLE IF EXISTS GroupNames;                                                                 \n"
            				+ " 	CREATE TEMPORARY TABLE GroupNames(                                                                         \n"
            				+ " 		GroupName VARCHAR(255) NOT NULL KEY                                                                    \n"
            				+ " 	);                                                                                                         \n"
            				+ "                                                                                                                \n"
            				+ " 	WHILE (LOCATE(DELIM, ValueString) > 0)                                                                     \n"
            				+ " 	DO                                                                                                         \n"
            				+ " 		SET @Loc = LOCATE(DELIM, ValueString);                                                                 \n"
            				+ " 		SET @NewValue = SUBSTRING(ValueString, 1, @Loc-1);                                                     \n"
            				+ " 		                                                                                                       \n"
            				+ " 		IF (NOT EXISTS(SELECT 1 FROM GroupNames WHERE GroupName = @NewValue)) THEN                             \n"
            				+ " 			INSERT INTO GroupNames SELECT @NewValue;                                                           \n"
            				+ " 		END IF;                                                                                                \n"
            				+ " 		                                                                                                       \n"
            				+ " 		SET ValueString = SUBSTRING(ValueString, @Loc + LENGTH(DELIM));                                        \n"
            				+ " 		                                                                                                       \n"
            				+ " 	END WHILE;                                                                                                 \n"
            				+ " 	                                                                                                           \n"
            				+ " 	IF (LENGTH(ValueString) > 0 AND NOT EXISTS(SELECT 1 FROM GroupNames WHERE GroupName = ValueString)) THEN   \n"
            				+ " 		INSERT INTO GroupNames SELECT ValueString;                                                             \n"
            				+ " 	END IF;                                                                                                    \n"
            				+ "                                                                                                                \n"
            				+ "                                                                                                                \n"
            				+ " 	SELECT 	                                                                                                   \n"
            				+ " 		s.snitch_world AS world                                                                                \n"
            				+ " 		, s.snitch_x AS X                                                                                      \n"
            				+ " 		, s.snitch_y AS Y                                                                                      \n"
            				+ " 		, s.snitch_z AS z                                                                                      \n"
            				+ " 		, TIMESTAMPDIFF(SECOND                                                                                 \n"
            				+ " 			, DATE_ADD(                                                                                        \n"
            				+ " 				UTC_TIMESTAMP()                                                                                \n"
            				+ " 				, INTERVAL                                                                                     \n"
            				+ " 					CASE WHEN s.snitch_should_log = 1 THEN -daysFromLastAdminVisitForLoggedSnitchCulling       \n"
            				+ " 					ELSE -daysFromLastAdminVisitForNonLoggedSnitchCulling                                      \n"
            				+ " 				END DAY                                                                                        \n"
            				+ " 			)                                                                                                  \n"
            				+ " 			,s.last_semi_owner_visit_date                                                                      \n"
            				+ " 		) AS TimeLeftAliveInSeconds                                                                            \n"
            				+ " 		, s.snitch_should_log AS DoesSnitchRegisterEvents                                                      \n"
            				+ " 		, s.snitch_group AS SnitchGroup					                                                       \n"
            				+ " 	 FROM                                                                                                      \n"
            				+ " 		{0} s                                                                                                  \n"
            				+ " 		INNER JOIN GroupNames filter                                                                           \n"
            				+ " 			ON filter.groupname = s.snitch_group                                                               \n"
            				+ " 	 ORDER BY CASE                                                                                             \n"
            				+ " 		WHEN                                                                                                   \n"
            				+ " 			(s.snitch_should_log = 1 AND daysFromLastAdminVisitForLoggedSnitchCulling >= 1)                    \n"
            				+ " 			OR (s.snitch_should_log = 0 AND daysFromLastAdminVisitForNonLoggedSnitchCulling >= 1)              \n"
            				+ " 			THEN 1                                                                                             \n"
            				+ " 		ELSE 0                                                                                                 \n"
            				+ " 		END DESC                                                                                               \n"
            				+ " 		, 5 ASC, s.snitch_id asc LIMIT skipRecordCount, itemsOnPage ;                                          \n"
            				+ "                                                                                                                \n"
            				+ " 	DROP TEMPORARY TABLE IF EXISTS GroupNames;                                                                 \n"
            				+ " 		                                                                                                       \n"
            				+ "                                                                                                                \n"
            				+ " END                                                                                                            \n"
            				, snitchsTbl));

        } catch (Exception ex) {
            String exMsg = ex.toString();
            if (!exMsg.contains("PROCEDURE GetJukeboxListForDelimitedGroup already exists")) {
                this.plugin.getLogger().log(Level.SEVERE, exMsg);
            }
        }
    }

    public PreparedStatement getNewInsertSnitchLogStmt() {
        return db.prepareStatement(String.format("INSERT INTO %s (snitch_id, snitch_log_time, "
                + "snitch_logged_action, snitch_logged_initiated_user,"
                + " snitch_logged_victim_user, snitch_logged_x, snitch_logged_y, snitch_logged_z, snitch_logged_materialid) "
                + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snitchDetailsTbl));
    }

    public PreparedStatement getNewUpdateSnitchSemiOwnerVisitStmt() {
        return db.prepareStatement(String.format("UPDATE %s SET last_semi_owner_visit_date = UTC_TIMESTAMP() WHERE snitch_id = ?;", snitchsTbl));
    }

    private void initializeStatements() {

        getAllSnitchesStmt = db.prepareStatement(String.format(
                "SELECT * FROM %s", snitchsTbl));

        getAllSnitchesByWorldStmt = db.prepareStatement(String.format(
                "SELECT * FROM %s WHERE snitch_world = ?", snitchsTbl));

        getLastSnitchID = db.prepareStatement(String.format(
                "SHOW TABLE STATUS LIKE '%s'", snitchsTbl));

        // statement to get LIMIT entries OFFSET from a number from the snitchesDetailsTbl based on a snitch_id from the main snitchesTbl
        // LIMIT ?,? means offset followed by max rows to return
        getSnitchLogStmt = db.prepareStatement(String.format(
                "SELECT * FROM %s"
                + " WHERE snitch_id=? ORDER BY snitch_log_time DESC LIMIT ?,?",
                snitchDetailsTbl));

        getSnitchLogGroupStmt = db.prepareStatement(MessageFormat.format(
            "SELECT {0}.snitch_name, {1}.*"
            + " FROM {1} INNER JOIN {0} ON {0}.snitch_id = {1}.snitch_id"
            + " WHERE {0}.snitch_group=? ORDER BY {1}.snitch_log_time DESC LIMIT ?,{2}",
            snitchsTbl, snitchDetailsTbl, logsPerPage));
        
        getSnitchListStmt = db.prepareStatement(MessageFormat.format(
        		"Call GetJukeboxListForDelimitedGroup(?, ?, {0}, {1}, ?, {2});"
        		, daysFromLastAdminVisitForLoggedSnitchCulling
        		, daysFromLastAdminVisitForNonLoggedSnitchCulling
        		, logsPerPage));

        // statement to get the ID of a snitch in the main snitchsTbl based on a Location (x,y,z, world)
        getSnitchIdFromLocationStmt = db.prepareStatement(String.format("SELECT snitch_id FROM %s"
                + " WHERE snitch_x=? AND snitch_y=? AND snitch_z=? AND snitch_world=?", snitchsTbl));

        // statement to insert a log entry into the snitchesDetailsTable
        insertSnitchLogStmt = db.prepareStatement(String.format(
                "INSERT INTO %s (snitch_id, snitch_log_time, snitch_logged_action, snitch_logged_initiated_user,"
                + " snitch_logged_victim_user, snitch_logged_x, snitch_logged_y, snitch_logged_z, snitch_logged_materialid) "
                + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snitchDetailsTbl));

        //
        insertNewSnitchStmt = db.prepareStatement(String.format(
                "INSERT INTO %s (snitch_world, snitch_name, snitch_x, snitch_y, snitch_z, snitch_group, snitch_cuboid_x, snitch_cuboid_y, snitch_cuboid_z, snitch_should_log, last_semi_owner_visit_date,allow_triggering_lever)"
                + " VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(),0)",
                snitchsTbl));

        //
        deleteSnitchLogStmt = db.prepareStatement(String.format(
                "DELETE FROM %s WHERE snitch_id=?",
                snitchDetailsTbl));

        //
        deleteSnitchStmt = db.prepareStatement(String.format(
                "DELETE FROM %s WHERE snitch_world=? AND snitch_x=? AND snitch_y=? AND snitch_z=?",
                snitchsTbl));

        //
        updateGroupStmt = db.prepareStatement(String.format(
                "UPDATE %s SET snitch_group=? WHERE snitch_world=? AND snitch_x=? AND snitch_y=? AND snitch_z=?",
                snitchsTbl));

        //
        updateCuboidVolumeStmt = db.prepareStatement(String.format(
                "UPDATE %s SET snitch_cuboid_x=?, snitch_cuboid_y=?, snitch_cuboid_z=?"
                + " WHERE snitch_world=? AND snitch_x=? AND snitch_y=? AND snitch_z=?",
                snitchsTbl));

        //
        updateSnitchNameStmt = db.prepareStatement(String.format(
                "UPDATE %s SET snitch_name=?"
                + " WHERE snitch_id=?",
                snitchsTbl));

        //
        updateSnitchToggleLeversStmt = db.prepareStatement(String.format(
                "UPDATE %s SET allow_triggering_lever=?"
                + " WHERE snitch_id=?",
                snitchsTbl));
        
        //
        updateSnitchGroupStmt = db.prepareStatement(String.format(
                "UPDATE %s SET snitch_group=?"
                + " WHERE snitch_id=?",
                snitchsTbl));

        //
        getAllSnitchIdsStmt = db.prepareStatement(String.format(
                "SELECT snitch_id FROM %s;", snitchsTbl));

        //
        cullSnitchEntriesStmt = db.prepareStatement(MessageFormat.format(
            "CALL CullSnitches({0}, {1}, {2});",
            minEntryLifetimeDays, maxEntryLifetimeDays, maxEntryCount));
        
        cullSnitchEntriesAndSnitchesBasedOnVisitDateStmt = db.prepareStatement(MessageFormat.format(
        		"Call CullSnitchesBasedOnLastVisitDate({0},{1});"
        		, daysFromLastAdminVisitForLoggedSnitchCulling,daysFromLastAdminVisitForNonLoggedSnitchCulling));
        
    }

    private void initializeLastSnitchId() {
        lastSnitchID = -1;
        try {
            ResultSet rsKey = getLastSnitchID.executeQuery();
            if (rsKey.next()) {
                lastSnitchID = rsKey.getInt("Auto_increment");
            }
        } catch (SQLException ex) {
            lastSnitchID = -1;
        }
        if (lastSnitchID == -1) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not determine the last snitch id!");
        }
    }

    public static String snitchKey(final Location loc) {
        return String.format(
                "World: %s X: %d Y: %d Z: %d",
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    protected class SnitchEnumerator implements Enumeration<Snitch> {
        JukeAlertLogger logger_;
        World world_;
        ResultSet rs_;
        Snitch next_;

        public SnitchEnumerator(JukeAlertLogger logger, World world) {
            logger_ = logger;
            world_ = world;
            try {
                getAllSnitchesByWorldStmt.setString(1, world_.getName());
                rs_ = getAllSnitchesByWorldStmt.executeQuery();
                next_ = getNextSnitch();
            } catch (SQLException ex) {
                logger_.plugin.getLogger().log(Level.SEVERE, "Couldn't get first snitch in World " + world_ + "!   " + ex.toString());
                rs_ = null;
                next_ = null;
            }
        }

        @Override
        public boolean hasMoreElements() {
            return next_ != null;
        }

        private Snitch getNextSnitch() {
            try {
                if (rs_ == null || !rs_.next()) {
                    rs_ = null;
                    return null;
                }
                double x = rs_.getInt("snitch_x");
                double y = rs_.getInt("snitch_y");
                double z = rs_.getInt("snitch_z");
                String groupName = rs_.getString("snitch_group");
                Faction group = groupMediator.getGroupByName(groupName);
                Location location = new Location(world_, x, y, z);
                if (group == null) {
                    JukeAlert.getInstance().log(String.format(
                        "Group not found for (%s,%d,%d,%d): %s",
                        world_.getName(), (int)x, (int)y, (int)z, groupName));
                }
                Snitch snitch = new Snitch(location, group, rs_.getBoolean("snitch_should_log"), rs_.getBoolean("allow_triggering_lever"));
                snitch.setId(rs_.getInt("snitch_id"));
                snitch.setName(rs_.getString("snitch_name"));
                return snitch;
            } catch (SQLException ex) {
                logger_.plugin.getLogger().log(Level.SEVERE, "Could not get all Snitches from World " + world_ + "!   " + ex.toString());
                rs_ = null;
            }
            return null;
        }

        @Override
        public Snitch nextElement() {
            if (next_ == null) {
                throw new NoSuchElementException();
            }
            Snitch retval = next_;
            next_ = getNextSnitch();
            if (next_ != null && lastSnitchID < next_.getId()) {
                lastSnitchID = next_.getId();
            }
            return retval;
        }
    }

    public Enumeration<Snitch> getAllSnitches(World world) {
        return new SnitchEnumerator(this, world);
    }

    public void saveAllSnitches() {
        //TODO: Save snitches.
        jukeinfobatch.flush();
    }

    /**
     * Gets
     *
     * @limit events about that snitch.
     * @param loc - the location of the snitch
     * @param offset - the number of entries to start at (10 means you start at
     * the 10th entry and go to
     * @limit)
     * @param limit - the number of entries to limit
     * @return a Map of String/Date objects of the snitch entries, formatted
     * nicely
     */
    public List<String> getSnitchInfo(Location loc, int offset) {
        List<String> info = new ArrayList<String>();

        // get the snitch's ID based on the location, then use that to get the snitch details from the snitchesDetail table
        int interestedSnitchId = -1;
        try {
            // params are x(int), y(int), z(int), world(tinyint), column returned: snitch_id (int)
            getSnitchIdFromLocationStmt.setInt(1, loc.getBlockX());
            getSnitchIdFromLocationStmt.setInt(2, loc.getBlockY());
            getSnitchIdFromLocationStmt.setInt(3, loc.getBlockZ());
            getSnitchIdFromLocationStmt.setString(4, loc.getWorld().getName());

            ResultSet snitchIdSet = getSnitchIdFromLocationStmt.executeQuery();

            // make sure we got a result
            boolean didFind = false;
            while (snitchIdSet.next()) {
                didFind = true;
                interestedSnitchId = snitchIdSet.getInt("snitch_id");
            }

            // only continue if we actually got a result from the first query
            if (!didFind) {
                this.plugin.getLogger().log(Level.SEVERE, "Didn't get any results trying to find a snitch in the snitches table at location " + loc);
            } else {
                GetSnitchInfoTask task = new GetSnitchInfoTask(plugin, interestedSnitchId, offset);
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
                return task.getInfo();
            }

        } catch (SQLException ex1) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not get Snitch Details! loc: " + loc, ex1);
        }

        return info;
    }

    public List<String> getSnitchInfo(int snitchId, int offset) {
        List<String> info = new ArrayList<String>();

        try {
            getSnitchLogStmt.setInt(1, snitchId);
            getSnitchLogStmt.setInt(2, offset);
            getSnitchLogStmt.setInt(3, logsPerPage);

            ResultSet set = getSnitchLogStmt.executeQuery();
            if (set.isBeforeFirst()) {
                while (set.next()) {
                    // TODO: need a function to create a string based upon what things we have / don't have in this result set
                    // so like if we have a block place action, then we include the x,y,z, but if its a KILL action, then we just say
                    // x killed y, etc
                    info.add(createInfoString(set, false));
                }
            }
        } catch (SQLException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not get Snitch Details from the snitchesDetail table using the snitch id " + snitchId, ex);
        }

        return info;
    }
    public List<String> getSnitchList(Player player, int offset) {
        List<String> info = new ArrayList<String>();

        try {
        	String uuidString = java.util.UUID.randomUUID().toString();
            UUID accountId = player.getUniqueId();
        	List<String> groups = groupMediator.getGroupsByAccount(accountId, true, true, false);
        	
        	StringBuilder sb = new StringBuilder();
        	for(String group : groups) {
        		sb.append(group);
        		sb.append(uuidString);
        	}
        	
        	getSnitchListStmt.setString(1, sb.toString());
        	getSnitchListStmt.setString(2, uuidString);
        	getSnitchListStmt.setInt(3, offset);
        	
            ResultSet set = getSnitchListStmt.executeQuery();
            if (set.isBeforeFirst()) {
                while (set.next()) {
                	
                	String snitchGroupString = set.getString("SnitchGroup");
                	String truncatedGroupString = ChatFiller.fillString(set.getString("SnitchGroup"), 20.0);
                	
                	if (!snitchGroupString.trim().equals(truncatedGroupString.trim())) {
                		truncatedGroupString = truncatedGroupString.substring(0, truncatedGroupString.length()-4) + ChatColor.GRAY +  "..." + ChatColor.WHITE;
                	}
                	
                    info.add(ChatColor.WHITE
                    		+ "  "
                    		+ ChatFiller.fillString(set.getString("world"), 11.0) 
                    		
                    		+ ChatFiller.fillString("[" + set.getString("x") + " " + set.getString("y") + " " + set.getString("z") + "]", 31.0)
                            
                    		+ ChatFiller.fillString(
                        			(((set.getInt("DoesSnitchRegisterEvents") == 1 && daysFromLastAdminVisitForLoggedSnitchCulling >= 1)
                        			|| (set.getInt("DoesSnitchRegisterEvents") == 0 && daysFromLastAdminVisitForNonLoggedSnitchCulling >= 1)) ? 
                        					 String.format("%.2f", ((set.getInt("TimeLeftAliveInSeconds") < 0 ? 0 : set.getInt("TimeLeftAliveInSeconds")) / 3600.0))  : ""), 14.0)
                                             
                     		+ truncatedGroupString
                			);
                }
            }
        } catch (SQLException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not get Snitch List using playername " + player.getDisplayName(), ex);
        }

        return info;
    }

    public List<String> getSnitchGroupInfo(String group, int offset) {
        List<String> info = new ArrayList<String>();

        try {
            getSnitchLogGroupStmt.setString(1, group);
            getSnitchLogGroupStmt.setInt(2, offset);

            ResultSet set = getSnitchLogGroupStmt.executeQuery();
            if (set.isBeforeFirst()) {
                while (set.next()) {
                    info.add(createInfoString(set, true));
                }
            }
        } catch (SQLException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not get Snitch Details from the snitchesDetail table using the snitch group " + group, ex);
        }

        return info;
    }

    public Boolean deleteSnitchInfo(Location loc) {
        Boolean completed = false;
        // get the snitch's ID based on the location, then use that to get the snitch details from the snitchesDetail table
        int interestedSnitchId = -1;
        jukeinfobatch.flush();
        try {
            // params are x(int), y(int), z(int), world(tinyint), column returned: snitch_id (int)
            getSnitchIdFromLocationStmt.setInt(1, loc.getBlockX());
            getSnitchIdFromLocationStmt.setInt(2, loc.getBlockY());
            getSnitchIdFromLocationStmt.setInt(3, loc.getBlockZ());
            getSnitchIdFromLocationStmt.setString(4, loc.getWorld().getName());
            ResultSet snitchIdSet = getSnitchIdFromLocationStmt.executeQuery();
            // make sure we got a result
            boolean didFind = false;
            while (snitchIdSet.next()) {
                didFind = true;
                interestedSnitchId = snitchIdSet.getInt("snitch_id");
            }

            // only continue if we actually got a result from the first query
            if (!didFind) {
                this.plugin.getLogger().log(Level.SEVERE, "Didn't get any results trying to find a snitch in the snitches table at location " + loc);
            } else {
                deleteSnitchInfo(interestedSnitchId);
            }

        } catch (SQLException ex1) {
            completed = false;
            this.plugin.getLogger().log(Level.SEVERE, "Could not get Snitch Details! loc: " + loc, ex1);
        }

        return completed;
    }

    public Boolean deleteSnitchInfo(int snitchId) {
        try {
            deleteSnitchLogStmt.setInt(1, snitchId);
            deleteSnitchLogStmt.execute();
            return true;
        } catch (SQLException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not delete Snitch Details from the snitchesDetail table using the snitch id " + snitchId, ex);
            return false;
        }
    }

    public JukeInfoBatch jukeinfobatch = new JukeInfoBatch(this);
    
    public void logSnitchVisit(Snitch snitch) {
    	jukeinfobatch.addLastVisitData(snitch);
    }

    /**
     * Logs info to a specific snitch with a time stamp.
     *
     * example:
     *
     * ------DATE-----------DETAIL------ 2013-4-24 12:14:35 : Bob made an entry
     * at [Nether(X: 56 Y: 87 Z: -1230)] 2013-4-25 12:14:35 : Bob broke a chest
     * at X: 896 Y: 1 Z: 8501 2013-4-28 12:14:35 : Bob killed Trevor. ----Type
     * /ja more to see more----
     *
     * @param snitch - the snitch that recorded this event, required
     * @param material - the block/item/whatever that was part of the event, if
     * there was one , null if no material was part of the event
     * @param loc - the location where this event occured, if any
     * @param date - the date this event occurred , required
     * @param action - the action that took place in this event
     * @param initiatedUser - the user who initiated the event, required
     * @param victimUser - the user who was victim of the event, can be null
     */
    public void logSnitchInfo(Snitch snitch, Material material, Location loc, Date date, LoggedAction action, String initiatedUser, String victimUser) {

        jukeinfobatch.addSet(snitch, material, loc, date, action, initiatedUser, victimUser);

        /*try {
         // snitchid
         insertSnitchLogStmt.setInt(1, snitch.getId());
         // snitch log time
         insertSnitchLogStmt.setTimestamp(2, new java.sql.Timestamp(new java.util.Date().getTime()));
         // snitch logged action
         insertSnitchLogStmt.setByte(3, (byte) action.getLoggedActionId());
         // initiated user
         insertSnitchLogStmt.setString(4, initiatedUser);

         // These columns, victimUser, location and materialid can all be null so check if it is an insert SQL null if it is

         // victim user
         if (victimUser != null) {
         insertSnitchLogStmt.setString(5, victimUser);
         } else {
         insertSnitchLogStmt.setNull(5, java.sql.Types.VARCHAR);
         }

         // location, x, y, z
         if (loc != null) {
         insertSnitchLogStmt.setInt(6, loc.getBlockX());
         insertSnitchLogStmt.setInt(7, loc.getBlockY());
         insertSnitchLogStmt.setInt(8, loc.getBlockZ());
         } else {
         insertSnitchLogStmt.setNull(6, java.sql.Types.INTEGER);
         insertSnitchLogStmt.setNull(7, java.sql.Types.INTEGER);
         insertSnitchLogStmt.setNull(8, java.sql.Types.INTEGER);
         }

         // materialid
         if (material != null) {
         insertSnitchLogStmt.setShort(9, (short) material.getId());
         } else {
         insertSnitchLogStmt.setNull(9, java.sql.Types.SMALLINT);
         }
         Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
         @Override
         public void run() {
         try {
         insertSnitchLogStmt.execute();
         } catch (SQLException ex) {
         Logger.getLogger(JukeAlertLogger.class.getName()).log(Level.SEVERE, null, ex);
         }
         }
         });
         //To change body of generated methods, choose Tools | Templates.

         } catch (SQLException ex) {
         this.plugin.getLogger().log(Level.SEVERE, String.format("Could not create snitch log entry! with snitch %s, "
         + "material %s, date %s, initiatedUser %s, victimUser %s", snitch, material, date, initiatedUser, victimUser), ex);
         }*/
    }

    /**
     * logs a message that someone killed an entity
     *
     * @param snitch - the snitch that recorded this event
     * @param player - the player that did the killing
     * @param entity - the entity that died
     */
    public void logSnitchEntityKill(Snitch snitch, Player player, Entity entity) {

        // There is no material or location involved in this event
        this.logSnitchInfo(snitch, null, null, new Date(), LoggedAction.KILL, player.getPlayerListName(), entity.getType().toString());
    }

    /**
     * Logs a message that someone killed another player
     *
     * @param snitch - the snitch that recorded this event
     * @param player - the player that did the killing
     * @param victim - the player that died
     */
    public void logSnitchPlayerKill(Snitch snitch, Player player, Player victim) {
        // There is no material or location involved in this event
        this.logSnitchInfo(snitch, null, null, new Date(), LoggedAction.KILL, player.getPlayerListName(), victim.getPlayerListName());
    }

    /**
     * Logs a message that someone ignited a block within the snitch's field
     *
     * @param snitch - the snitch that recorded this event
     * @param player - the player that did the ignition
     * @param block - the block that was ignited
     */
    public void logSnitchIgnite(Snitch snitch, Player player, Block block) {
        // There is no material or location involved in this event
        this.logSnitchInfo(snitch, block.getType(), block.getLocation(), new Date(), LoggedAction.IGNITED, player.getPlayerListName(), null);
    }

    /**
     * Logs a message that someone entered the snitch's field
     *
     * @param snitch - the snitch that recorded this event
     * @param player - the player that entered the snitch's field
     * @param loc - the location of where the player entered
     */
    public void logSnitchEntry(Snitch snitch, Location loc, Player player) {

        // no material or victimUser for this event
        this.logSnitchInfo(snitch, null, loc, new Date(), LoggedAction.ENTRY, player.getPlayerListName(), null);
    }

	/**
	 * Logs a message that someone logged in in the snitch's field
	 *
	 * @param snitch - the snitch that recorded this event
	 * @param player - the player that logged in in the snitch's field
	 * @param loc - the location of where the player logged in at
	 */
	public void logSnitchLogin(Snitch snitch, Location loc, Player player) {
		// no material or victimUser for this event
		this.logSnitchInfo(snitch, null, loc, new Date(), LoggedAction.LOGIN, player.getPlayerListName(), null);
	}

	/**
	 * Logs a message that someone logged out in the snitch's field
	 *
	 * @param snitch - the snitch that recorded this event
	 * @param player - the player that logged out in the snitch's field
	 * @param loc - the location of where the player logged out at
	 */
	public void logSnitchLogout(Snitch snitch, Location loc, Player player) {
		// no material or victimUser for this event
		this.logSnitchInfo(snitch, null, loc, new Date(), LoggedAction.LOGOUT, player.getPlayerListName(), null);
	}

    /**
     * Logs a message that someone broke a block within the snitch's field
     *
     * @param snitch - the snitch that recorded this event
     * @param player - the player that broke the block
     * @param block - the block that was broken
     */
    public void logSnitchBlockBreak(Snitch snitch, Player player, Block block) {

        // no victim user in this event
        this.logSnitchInfo(snitch, block.getType(), block.getLocation(), new Date(), LoggedAction.BLOCK_BREAK, player.getPlayerListName(), null);
    }

    /**
     * Logs a message that someone placed a block within the snitch's field
     *
     * @param snitch - the snitch that recorded this event
     * @param player - the player that placed the block
     * @param block - the block that was placed
     */
    public void logSnitchBlockPlace(Snitch snitch, Player player, Block block) {
        // no victim user in this event
        this.logSnitchInfo(snitch, block.getType(), block.getLocation(), new Date(), LoggedAction.BLOCK_PLACE, player.getPlayerListName(), null);
    }

    /**
     * Logs a message that someone emptied a bucket within the snitch's field
     *
     * @param snitch - the snitch that recorded this event
     * @param player - the player that emptied the bucket
     * @param loc - the location of where the bucket empty occurred
     * @param item - the ItemStack representing the bucket that the player
     * emptied
     */
    public void logSnitchBucketEmpty(Snitch snitch, Player player, Location loc, ItemStack item) {
        // no victim user in this event
        this.logSnitchInfo(snitch, item.getType(), loc, new Date(), LoggedAction.BUCKET_EMPTY, player.getPlayerListName(), null);
    }

    /**
     * Logs a message that someone filled a bucket within the snitch's field
     *
     * @param snitch - the snitch that recorded this event
     * @param player - the player that filled the bucket
     * @param block - the block that was 'put into' the bucket
     */
    public void logSnitchBucketFill(Snitch snitch, Player player, Block block) {
        // TODO: should we take a block or a ItemStack as a parameter here?
        // JM: I think it'll be fine either way, most griefing is done with with block placement and this could be updated fairly easily

        // no victim user in this event
        this.logSnitchInfo(snitch, block.getType(), block.getLocation(), new Date(), LoggedAction.BUCKET_FILL, player.getPlayerListName(), null);
    }

    /**
     * Logs a message that someone used a block within the snitch's field
     *
     * @param snitch - the snitch that recorded this event
     * @param player - the player that used something
     * @param block - the block that was used
     */
    public void logUsed(Snitch snitch, Player player, Block block) {
        // TODO: what should we use to identify what was used? Block? Material?
        //JM: Let's keep this consistent with block plament
        this.logSnitchInfo(snitch, block.getType(), block.getLocation(), new Date(), LoggedAction.BLOCK_USED, player.getPlayerListName(), null);
    }

    //Logs the snitch being placed at World, x, y, z in the database.
    public void logSnitchPlace(final String world, final String group, final String name, final int x, final int y, final int z, final boolean shouldLog) {
        final ConfigManager lockedConfigManager = this.configManager;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    jukeinfobatch.flush();
                	synchronized(insertNewSnitchStmt) {
	                    insertNewSnitchStmt.setString(1, world);
	                    insertNewSnitchStmt.setString(2, name);
	                    insertNewSnitchStmt.setInt(3, x);
	                    insertNewSnitchStmt.setInt(4, y);
	                    insertNewSnitchStmt.setInt(5, z);
	                    insertNewSnitchStmt.setString(6, group);
	                    insertNewSnitchStmt.setInt(7, lockedConfigManager.getDefaultCuboidSize());
	                    insertNewSnitchStmt.setInt(8, lockedConfigManager.getDefaultCuboidSize());
	                    insertNewSnitchStmt.setInt(9, lockedConfigManager.getDefaultCuboidSize());
	                    insertNewSnitchStmt.setBoolean(10, shouldLog);
	                    insertNewSnitchStmt.execute();
                	}
                } catch (SQLException ex) {
                    Logger.getLogger(JukeAlertLogger.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    //Removes the snitch at the location of World, X, Y, Z from the database.
    public void logSnitchBreak(final String world, final int x, final int y, final int z) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    jukeinfobatch.flush();
                    synchronized (deleteSnitchStmt) {
                        deleteSnitchStmt.setString(1, world);
                        deleteSnitchStmt.setInt(2, (int) Math.floor(x));
                        deleteSnitchStmt.setInt(3, (int) Math.floor(y));
                        deleteSnitchStmt.setInt(4, (int) Math.floor(z));
                        deleteSnitchStmt.execute();
                    }
                } catch (SQLException ex) {
                    Logger.getLogger(JukeAlertLogger.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    //Changes the group of which the snitch is registered to at the location of loc in the database.
    public void updateGroupSnitch(final Location loc, final String group) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (updateGroupStmt) {
                        updateGroupStmt.setString(1, group);
                        updateGroupStmt.setString(2, loc.getWorld().getName());
                        updateGroupStmt.setInt(3, loc.getBlockX());
                        updateGroupStmt.setInt(4, loc.getBlockY());
                        updateGroupStmt.setInt(5, loc.getBlockZ());
                        updateGroupStmt.execute();
                    }
                } catch (SQLException ex) {
                    Logger.getLogger(JukeAlertLogger.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    //Updates the cuboid size of the snitch in the database.
    public void updateCubiodSize(final Location loc, final int x, final int y, final int z) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (updateCuboidVolumeStmt) {
                        updateCuboidVolumeStmt.setInt(1, x);
                        updateCuboidVolumeStmt.setInt(2, y);
                        updateCuboidVolumeStmt.setInt(3, z);
                        updateCuboidVolumeStmt.setString(4, loc.getWorld().getName());
                        updateCuboidVolumeStmt.setInt(5, loc.getBlockX());
                        updateCuboidVolumeStmt.setInt(6, loc.getBlockY());
                        updateCuboidVolumeStmt.setInt(7, loc.getBlockZ());
                        updateCuboidVolumeStmt.execute();
                    }
                } catch (SQLException ex) {
                    Logger.getLogger(JukeAlertLogger.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    //Updates the name of the snitch in the database.
    public void updateSnitchName(final Snitch snitch, final String name) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (updateSnitchNameStmt) {
                        updateSnitchNameStmt.setString(1, name);
                        updateSnitchNameStmt.setInt(2, snitch.getId());
                        updateSnitchNameStmt.execute();
                    }
                } catch (SQLException ex) {
                    Logger.getLogger(JukeAlertLogger.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    //Updates the ToggleLevers of the snitch in the database.
    public void updateSnitchToggleLevers(final Snitch snitch, final Boolean isEnabled) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (updateSnitchToggleLeversStmt) {
                    	updateSnitchToggleLeversStmt.setBoolean(1, isEnabled);
                    	updateSnitchToggleLeversStmt.setInt(2, snitch.getId());
                    	updateSnitchToggleLeversStmt.execute();
                    }
                } catch (SQLException ex) {
                    Logger.getLogger(JukeAlertLogger.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    //Updates the group of the snitch in the database.
    public void updateSnitchGroup(final Snitch snitch, final String group) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (updateSnitchGroupStmt) {
                        updateSnitchGroupStmt.setString(1, group);
                        updateSnitchGroupStmt.setInt(2, snitch.getId());
                        updateSnitchGroupStmt.execute();
                    }
                } catch (SQLException ex) {
                    Logger.getLogger(JukeAlertLogger.class.getName()).log(Level.SEVERE, null, ex);
                }
            }
        });
    }

    public Integer getLastSnitchID() {
        return lastSnitchID;
    }

    public void increaseLastSnitchID() {
        lastSnitchID++;
    }

    public void logSnitchBlockBurn(Snitch snitch, Block block) {
        this.logSnitchInfo(snitch, block.getType(), block.getLocation(), new Date(), LoggedAction.BLOCK_BURN, "", snitchDetailsTbl);
    }

    public String createInfoString(ResultSet set, boolean isGroup) {
        String resultString = ChatColor.RED + "Error!";
        try {
            final int id = set.getInt("snitch_details_id");
            final int snitchID = set.getInt("snitch_id");

            final Snitch snitch = JukeAlert.getInstance().getSnitchManager().getSnitch(snitchID);
            final String name = (snitch == null) ? "" : snitch.getName();

            final String initiator = ChatFiller.fillString(set.getString("snitch_logged_initiated_user"), 20.0);
            final String victim = set.getString("snitch_logged_victim_user");
            final int actionValue = set.getByte("snitch_logged_action");
            final LoggedAction action = LoggedAction.getFromId(actionValue);
            final int material = set.getInt("snitch_logged_materialid");

            final int x = set.getInt("snitch_logged_X");
            final int y = set.getInt("snitch_logged_Y");
            final int z = set.getInt("snitch_logged_Z");

            final String timestamp = new SimpleDateFormat("MM-dd HH:mm").format(set.getTimestamp("snitch_log_time"));

            String actionString = "BUG";
            ChatColor actionColor = ChatColor.WHITE;
            boolean useCoordinate = false;
            int actionTextType = 0;
            switch(action) {
                case ENTRY:
                    actionString = "Entry";
                    actionColor = ChatColor.BLUE;
                    actionTextType = 1;
                    break;
                case LOGIN:
                    actionString = "Login";
                    actionColor = ChatColor.GREEN;
                    actionTextType = 1;
                    break;
                case LOGOUT:
                    actionString = "Logout";
                    actionColor = ChatColor.GREEN;
                    actionTextType = 1;
                    break;
                case BLOCK_BREAK:
                    actionString = "Block Break";
                    actionColor = ChatColor.DARK_RED;
                    actionTextType = 2;
                    break;
                case BLOCK_PLACE:
                    actionString = "Block Place";
                    actionColor = ChatColor.DARK_RED;
                    actionTextType = 2;
                    break;
                case BLOCK_BURN:
                    actionString = "Block Burn";
                    actionColor = ChatColor.DARK_RED;
                    actionTextType = 2;
                    break;
                case IGNITED:
                    actionString = "Ignited";
                    actionColor = ChatColor.GOLD;
                    actionTextType = 2;
                    break;
                case USED:
                case BLOCK_USED:
                    actionString = "Used";
                    actionColor = ChatColor.GREEN;
                    actionTextType = 2;
                    break;
                case BUCKET_EMPTY:
                    actionString = "Bucket Empty";
                    actionColor = ChatColor.DARK_RED;
                    actionTextType = 2;
                    break;
                case BUCKET_FILL:
                    actionString = "Bucket Fill";
                    actionColor = ChatColor.GREEN;
                    actionTextType = 2;
                    break;
                case KILL:
                    actionString = "Killed";
                    actionColor = ChatColor.DARK_RED;
                    actionTextType = 3;
                    break;
                default:
                case UNKNOWN:
                    this.plugin.getLogger().log(Level.SEVERE, String.format(
                        "Unknown LoggedAction: {0}", actionValue));
                    break;
            }
            if (isGroup) {
                actionTextType = 4;
            }
            String actionText = "";
            switch(actionTextType) {
                default:
                case 0:
                    break;
                case 1:
                    actionText = timestamp;
                    break;
                case 2:
                    actionText = String.format("%d [%d %d %d]", material, x, y, z);
                    break;
                case 3:
                    actionText = victim;
                    break;
                case 4:
                    actionText = name;
                    break;
            }
            actionString = ChatFiller.fillString(actionString, 20.0);
            final String formatting = "  %s%s %s%s%s %s";
            resultString = String.format(formatting, ChatColor.GOLD, initiator, actionColor, actionString, ChatColor.WHITE, actionText);
        } catch (SQLException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not get Snitch Details!");
        }

        return resultString;
    }

    public Set<Integer> getAllSnitchIds() {
        ResultSet rsKey = null;
        Set<Integer> snitchIds = new TreeSet<Integer>();
        try {
            rsKey = getAllSnitchIdsStmt.executeQuery();
            while (rsKey.next()) {
                int snitchId = rsKey.getInt("snitch_id");
                snitchIds.add(snitchId);
            }
            return snitchIds;
        } catch (SQLException ex) {
            this.plugin.getLogger().log(Level.SEVERE, "Could not get Snitch IDs! " + ex.toString());
        } finally {
            if (rsKey != null) {
                try { rsKey.close(); } catch (Exception ex) {}
            }
        }
        return null;
    }

    public void cullSnitchEntries() {
        this.plugin.getLogger().log(Level.INFO, "Culling snitch entries...");
        try {
            cullSnitchEntriesStmt.executeUpdate();
            cullSnitchEntriesAndSnitchesBasedOnVisitDateStmt.executeUpdate();
            this.plugin.getLogger().log(Level.INFO, "Snitch entry culling complete!");
        } catch (SQLException ex) {
            this.plugin.getLogger().log(
                Level.SEVERE, String.format("Could not entry cull: %s", ex.toString()));
        }
    }
}
