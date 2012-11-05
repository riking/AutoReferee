package org.mctourney.AutoReferee;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import org.mctourney.AutoReferee.listeners.ZoneListener;
import org.mctourney.AutoReferee.util.*;

import org.apache.commons.collections.CollectionUtils;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Maps;

public class AutoRefTeam implements Comparable<AutoRefTeam>
{
	// reference to the match
	private AutoRefMatch match = null;

	public AutoRefMatch getMatch()
	{ return match; }

	// player information
	private Set<AutoRefPlayer> players = Sets.newHashSet();

	public Set<AutoRefPlayer> getPlayers()
	{
		if (players != null) return players;
		return Sets.newHashSet();
	}

	public String getPlayerList()
	{
		Set<String> plist = Sets.newHashSet();
		for (AutoRefPlayer apl : getPlayers())
			plist.add(apl.getPlayerName());
		if (plist.size() == 0) return "{empty}";
		return StringUtils.join(plist, ", ");
	}

	private Set<OfflinePlayer> expectedPlayers = Sets.newHashSet();

	public void addExpectedPlayer(OfflinePlayer op)
	{ expectedPlayers.add(op); }

	public void addExpectedPlayer(String name)
	{ addExpectedPlayer(AutoReferee.getInstance().getServer().getOfflinePlayer(name)); }

	public Set<OfflinePlayer> getExpectedPlayers()
	{ return expectedPlayers; }

	// team's name, may or may not be color-related
	private String name = null;
	private String customName = null;

	// determine the name provided back from this team
	public String getRawName()
	{
		if (customName != null) return customName;
		return name;
	}

	public String getTag()
	{ return getRawName().toLowerCase().replaceAll("[^a-z0-9]+", ""); }

	public void setName(String name)
	{
		// send name change event before we actually change the name
		match.messageReferees("team", getRawName(), "name", name);

		String oldName = getName();
		customName = name;

		match.broadcast(oldName + " is now known as " + getName());
	}

	public String getName()
	{ return color + getRawName() + ChatColor.RESET; }

	// color to use for members of this team
	private ChatColor color = ChatColor.WHITE;

	public ChatColor getColor()
	{ return color; }

	public void setColor(ChatColor color)
	{ this.color = color; }

	// maximum size of a team (for manual mode only)
	public int maxSize = 0;

	// is this team ready to play?
	private boolean ready = false;

	public boolean isReady()
	{ return ready; }

	public void setReady(boolean ready)
	{
		if (ready == this.ready) return;
		this.ready = ready;

		for (Player pl : getMatch().getWorld().getPlayers())
			pl.sendMessage(getName() + " is now marked as " +
				ChatColor.DARK_GRAY + (this.ready ? "READY" : "NOT READY"));
		if (!this.ready) getMatch().cancelCountdown();
	}

	public boolean isEmptyTeam()
	{ return getPlayers().size() == 0 && getExpectedPlayers().size() == 0; }

	private Location lastObjectiveLocation = null;

	public Location getLastObjectiveLocation()
	{ return lastObjectiveLocation; }

	public void setLastObjectiveLocation(Location loc)
	{
		lastObjectiveLocation = loc;
		getMatch().setLastObjectiveLocation(loc);
	}

	private static final Vector HALF_BLOCK_VECTOR = new Vector(0.5, 0.5, 0.5);

	public Location getVictoryMonumentLocation()
	{
		Vector vmin = null, vmax = null;
		for (WinCondition wc : winConditions)
		{
			Vector v = wc.getLocation().toVector().add(HALF_BLOCK_VECTOR);
			vmin = vmin == null ? v : Vector.getMinimum(vmin, v);
			vmax = vmax == null ? v : Vector.getMaximum(vmax, v);
		}

		World w = getMatch().getWorld();
		return vmin.getMidpoint(vmax).toLocation(w);
	}

	// list of regions
	private Set<AutoRefRegion> regions = null;

	public Set<AutoRefRegion> getRegions()
	{ return regions; }

	// location of custom spawn
	private Location spawn;

	public void setSpawnLocation(Location loc)
	{
		getMatch().broadcast("Set " + getName() + "'s spawn to " +
			BlockVector3.fromLocation(loc).toCoords());
		this.spawn = loc;
	}

	public Location getSpawnLocation()
	{ return spawn == null ? match.getWorldSpawn() : spawn; }

	// win-conditions
	public class WinCondition
	{
		private Location loc;
		private BlockData bd;
		private int range;

		public WinCondition(Location loc, BlockData bd, int range)
		{ this.loc = loc; this.bd = bd; this.range = range; }

		public Location getLocation()
		{ return loc; }

		public BlockData getBlockData()
		{ return bd; }

		public int getInexactRange()
		{ return range; }
	}

	public Set<WinCondition> winConditions;

	// status of objectives
	public enum GoalStatus
	{
		NONE("none"),
		SEEN("found"),
		CARRYING("carry"),
		PLACED("vm");

		private String messageText;

		private GoalStatus(String mtext)
		{ messageText = mtext; }

		@Override
		public String toString()
		{ return messageText; }
	}

	public Map<BlockData, GoalStatus> objectiveStatus;

	// does a provided search string match this team?
	public boolean matches(String needle)
	{
		if (needle == null) return false;
		needle = needle.toLowerCase();

		String a = name, b = customName;
		if (b != null && -1 != needle.indexOf(b.toLowerCase())) return true;
		if (a != null && -1 != needle.indexOf(a.toLowerCase())) return true;
		return false;
	}

	public void startMatch()
	{
		objectiveStatus = Maps.newHashMap();
		for (BlockData obj : getObjectives())
			objectiveStatus.put(obj, GoalStatus.NONE);

		for (AutoRefPlayer apl : getPlayers())
		{
			apl.heal();
			apl.updateCarrying();
		}
	}

	// a factory for processing config maps
	@SuppressWarnings("unchecked")
	public static AutoRefTeam fromMap(Map<String, Object> conf, AutoRefMatch match)
	{
		AutoRefTeam newTeam = new AutoRefTeam();
		newTeam.color = ChatColor.RESET;
		newTeam.maxSize = 0;

		newTeam.match = match;
		World w = match.getWorld();

		// get name from map
		if (!conf.containsKey("name")) return null;
		newTeam.name = (String) conf.get("name");

		// get the color from the map
		if (conf.containsKey("color"))
		{
			String clr = ((String) conf.get("color")).toUpperCase();
			try { newTeam.color = ChatColor.valueOf(clr); }
			catch (IllegalArgumentException e) { }
		}

		// initialize this team for referees
		match.messageReferees("team", newTeam.getRawName(), "init");
		match.messageReferees("team", newTeam.getRawName(), "color", newTeam.color.toString());

		// get the max size from the map
		if (conf.containsKey("maxsize"))
		{
			Integer msz = (Integer) conf.get("maxsize");
			if (msz != null) newTeam.maxSize = msz.intValue();
		}

		newTeam.regions = Sets.newHashSet();
		if (conf.containsKey("regions"))
		{
			List<String> coordstrings = (List<String>) conf.get("regions");
			if (coordstrings != null) for (String coords : coordstrings)
			{
				AutoRefRegion creg = AutoRefRegion.fromCoords(coords);
				if (creg != null) newTeam.regions.add(creg);
			}
		}

		newTeam.spawn = !conf.containsKey("spawn") ? null :
			BlockVector3.fromCoords((String) conf.get("spawn")).toLocation(w);

		// setup both objective-based data-structures together
		// -- avoids an NPE with getObjectives()
		newTeam.winConditions = Sets.newHashSet();
		if (conf.containsKey("win-condition"))
		{
			List<String> slist = (List<String>) conf.get("win-condition");
			if (slist != null) for (String s : slist)
			{
				String[] sp = s.split(":");

				int range = sp.length > 2 ? Integer.parseInt(sp[2]) : match.getInexactRange();
				newTeam.addWinCondition(BlockVector3.fromCoords(sp[0]).toLocation(w),
					BlockData.fromString(sp[1]), range);
			}
		}

		newTeam.players = Sets.newHashSet();
		return newTeam;
	}

	public Map<String, Object> toMap()
	{
		Map<String, Object> map = Maps.newHashMap();

		// add name to the map
		map.put("name", name);

		// add string representation of the color
		map.put("color", color.name());

		// add the maximum team size
		map.put("maxsize", new Integer(maxSize));

		// set the team spawn (if there is a custom spawn)
		if (spawn != null) map.put("spawn", BlockVector3.fromLocation(spawn).toCoords());

		// convert the win conditions to strings
		List<String> wcond = Lists.newArrayList();
		for (WinCondition wc : winConditions)
		{
			String range = "";
			if (wc.getInexactRange() != match.getInexactRange())
				range = ":" + wc.getInexactRange();

			wcond.add(BlockVector3.fromLocation(wc.getLocation()).toCoords()
				+ ":" + wc.getBlockData().toString() + range);
		}

		// add the win condition list
		map.put("win-condition", wcond);

		// convert regions to strings
		List<String> regstr = Lists.newArrayList();
		for (AutoRefRegion reg : regions) regstr.add(reg.toCoords());

		// add the region list
		map.put("regions", regstr);

		// return the map
		return map;
	}

	public AutoRefPlayer getPlayer(String name)
	{
		AutoRefPlayer bapl = null;
		if (name != null)
		{
			int score, b = Integer.MAX_VALUE;
			for (AutoRefPlayer apl : players)
			{
				score = apl.nameSearch(name);
				if (score < b) { b = score; bapl = apl; }
			}
		}
		return bapl;
	}

	public AutoRefPlayer getPlayer(Player pl)
	{ return pl == null ? null : getPlayer(pl.getName()); }

	protected void addPlayer(AutoRefPlayer apl)
	{ apl.setTeam(this); this.players.add(apl); }

	protected boolean removePlayer(AutoRefPlayer apl)
	{ return this.players.remove(apl); }

	public boolean join(Player pl)
	{ return join(pl, false); }

	public boolean join(Player pl, boolean force)
	{
		// if this player is using the client mod, they may not join
		if (pl.getListeningPluginChannels().contains(AutoReferee.REFEREE_PLUGIN_CHANNEL))
		{
			if (!getMatch().isReferee(pl)) pl.sendMessage("You may not join a team with a modified client");
			String warning = ChatColor.DARK_GRAY + pl.getName() + " attempted to join " + this.getName() +
				ChatColor.DARK_GRAY + " with a modified client";
			for (Player ref : getMatch().getReferees(true)) ref.sendMessage(warning);
			return false;
		}

		// create an APL object for this player.
		AutoRefPlayer apl = new AutoRefPlayer(pl, this);

		// quit if they are already on this team
		if (players.contains(apl)) return true;

		// if the match is in progress, no one may join
		if (!match.getCurrentState().isBeforeMatch() && !force) return false;

		// prepare the player
		if (match != null && !match.getCurrentState().inProgress())
			pl.teleport(getSpawnLocation());
		pl.setGameMode(GameMode.SURVIVAL);

		this.addPlayer(apl);
		match.messageReferees("team", getRawName(), "player", "+" + apl.getPlayerName());
		match.messageReferees("player", apl.getPlayerName(), "login");

		String colorName = getPlayerName(pl);
		match.broadcast(colorName + " has joined " + getName());

		//FIXME if (pl.isOnline() && (pl instanceof Player))
		//	((Player) pl).setPlayerListName(StringUtils.substring(colorName, 0, 16));

		match.setupSpectators();
		match.checkTeamsReady();
		return true;
	}

	public boolean leave(Player pl)
	{ return leave(pl, false); }

	public boolean leave(Player pl, boolean force)
	{
		// if the match is in progress, no one may leave their team
		if (!match.getCurrentState().isBeforeMatch() && !force) return false;

		// create an APL object for this player.
		AutoRefPlayer apl = new AutoRefPlayer(pl);
		if (!this.removePlayer(apl)) return false;

		String colorName = getPlayerName(pl);
		match.broadcast(colorName + " has left " + getName());
		pl.teleport(match.getWorldSpawn());

		if (pl.isOnline() && (pl instanceof Player))
			((Player) pl).setPlayerListName(pl.getName());

		match.messageReferees("team", getRawName(), "player", "-" + apl.getPlayerName());

		match.setupSpectators();
		match.checkTeamsReady();
		return true;
	}

	public String getPlayerName(Player pl)
	{
		AutoRefPlayer apl = new AutoRefPlayer(pl);
		if (!players.contains(apl)) return pl.getName();
		return color + pl.getName() + ChatColor.RESET;
	}

	// TRUE = this location *is* within this team's regions
	// FALSE = this location is *not* within team's regions
	public boolean checkPosition(Location loc)
	{
		// is the provided location owned by this team?
		return distanceToClosestRegion(loc) < ZoneListener.SNEAK_DISTANCE;
	}

	public double distanceToClosestRegion(Location loc)
	{
		double distance = match.getStartRegion().distanceToRegion(loc);
		if (regions != null) for ( CuboidRegion reg : regions ) if (distance > 0)
			distance = Math.min(distance, reg.distanceToRegion(loc));
		return distance;
	}

	public boolean canEnter(Location loc)
	{ return canEnter(loc, ZoneListener.SNEAK_DISTANCE); }

	public boolean canEnter(Location loc, Double dist)
	{
		double distance = match.getStartRegion().distanceToRegion(loc);
		if (regions != null) for ( AutoRefRegion reg : regions ) if (distance > 0)
		{
			distance = Math.min(distance, reg.distanceToRegion(loc));
			if (!reg.canEnter() && reg.distanceToRegion(loc) <= dist) return false;
		}
		return distance <= dist;
	}

	public boolean canBuild(Location loc)
	{
		// start region is a permanent no-build zone
		if (getMatch().inStartRegion(loc)) return false;

		boolean build = false;
		if (regions != null) for ( AutoRefRegion reg : regions )
			if (reg.contains(BlockVector3.fromLocation(loc)))
			{ build = true; if (!reg.canBuild()) return false; }
		return build;
	}

	public void addWinCondition(Block block, int range)
	{
		// if the block is null, forget it
		if (block == null) return;

		// add the block data to the win-condition listing
		BlockData bd = BlockData.fromBlock(block);
		this.addWinCondition(block.getLocation(), bd, range);
	}

	public void addWinCondition(Location loc, BlockData bd, int range)
	{
		// if the block is null, forget it
		if (loc == null || bd == null) return;

		Set<BlockData> prevObj = getObjectives();
		winConditions.add(new WinCondition(loc, bd, range));
		Set<BlockData> newObj = getObjectives();

		newObj.removeAll(prevObj);
		for (BlockData nbd : newObj) match.messageReferees(
			"team", this.getRawName(), "obj", "+" + nbd.toString());

		// broadcast the update
		for (Player cfg : loc.getWorld().getPlayers()) if (cfg.hasPermission("autoreferee.configure"))
			cfg.sendMessage(bd.getName() + " is now a win condition for " + getName() +
				" @ " + BlockVector3.fromLocation(loc).toCoords());
	}

	public Set<BlockData> getObjectives()
	{
		Set<BlockData> objectives = Sets.newHashSet();
		for (WinCondition wc : winConditions)
			objectives.add(wc.getBlockData());
		objectives.remove(BlockData.AIR);
		return objectives;
	}

	public void updateObjectives()
	{
		int inexactRange = getMatch().getInexactRange();
		objloop: for (BlockData bd : getObjectives())
		{
			for (WinCondition wc : winConditions)
				if (bd.equals(wc.getBlockData()))
			{
				if (getMatch().blockInRange(bd, wc.getLocation(), inexactRange) != null)
				{ setObjectiveStatus(bd, GoalStatus.PLACED); continue objloop; }
			}

			for (AutoRefPlayer apl : getPlayers())
			{
				if (!apl.getCarrying().contains(bd)) continue;
				setObjectiveStatus(bd, GoalStatus.CARRYING);
				continue objloop;
			}

			if (getObjectiveStatus(bd) != GoalStatus.NONE)
			{ setObjectiveStatus(bd, GoalStatus.SEEN); continue; }
		}
	}

	public void setObjectiveStatus(BlockData objective, GoalStatus status)
	{
		// if this is going to be a proper update to an established objective
		if (objectiveStatus.containsKey(objective) &&
			objectiveStatus.get(objective) != status)
		{
			// message referees and update the objective status
			getMatch().messageReferees("team", getRawName(),
				"state", objective.toString(), status.toString());
			objectiveStatus.put(objective, status);
		}
	}

	public GoalStatus getObjectiveStatus(BlockData objective)
	{ return objectiveStatus == null ? GoalStatus.NONE : objectiveStatus.get(objective); }

	public Map<BlockData, GoalStatus> getObjectiveStatuses()
	{ return Maps.newHashMap(objectiveStatus); }

	private int objCount(GoalStatus status)
	{ return CollectionUtils.cardinality(status, objectiveStatus.values()); }

	public int getObjectivesPlaced()
	{ return objCount(GoalStatus.PLACED); }

	public int getObjectivesFound()
	{ return objectiveStatus.values().size() - objCount(GoalStatus.NONE); }

	public void updateCarrying(AutoRefPlayer apl, Set<BlockData> carrying, Set<BlockData> newCarrying)
	{
		match.updateCarrying(apl, carrying, newCarrying);
		this.updateObjectives();
	}

	public void updateHealthArmor(AutoRefPlayer apl,
			int currentHealth, int currentArmor, int newHealth, int newArmor)
	{
		match.updateHealthArmor(apl,
			currentHealth, currentArmor, newHealth, newArmor);
	}

	public int compareTo(AutoRefTeam team)
	{ return this.getRawName().compareTo(team.getRawName()); }

	public static void switchTeams(AutoRefTeam team1, AutoRefTeam team2)
	{
		// no work to be done
		if (team1 == null || team2 == null || team1 == team2) return;

		// must be in the same match
		if (team1.getMatch() != team2.getMatch()) return;

		// switch the sets of players
		Set<AutoRefPlayer> t1apls = team1.getPlayers();
		Set<AutoRefPlayer> t2apls = team2.getPlayers();

		team1.players = t2apls;
		team2.players = t1apls;

		for (AutoRefPlayer apl1 : team1.getPlayers()) apl1.setTeam(team1);
		for (AutoRefPlayer apl2 : team2.getPlayers()) apl2.setTeam(team2);

		// switch the custom names
		String t1cname = team1.customName;
		String t2cname = team2.customName;

		team1.customName = t2cname;
		team2.customName = t1cname;
	}
}
