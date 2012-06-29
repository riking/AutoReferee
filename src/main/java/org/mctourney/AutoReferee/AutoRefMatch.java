package org.mctourney.AutoReferee;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.material.*;

import org.mctourney.AutoReferee.AutoReferee.MatchStarter;
import org.mctourney.AutoReferee.AutoReferee.eMatchStatus;

import org.mctourney.AutoReferee.util.CuboidRegion;
import org.mctourney.AutoReferee.util.Vector3;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class AutoRefMatch
{
	// world this match is taking place on
	public World world;
	
	// time to set the world to at the start of the match
	public long startTime = 8000L;
	
	// status of the match
	public eMatchStatus currentState = eMatchStatus.NONE;
	
	// teams participating in the match
	public Set<AutoRefTeam> teams = null;
	
	// region defined as the "start" region (safe zone)
	public CuboidRegion startRegion = null;
	
	// name of the match
	public String matchName = "Scheduled Match";
	
	// configuration information for the world
	public File worldConfigFile;
	public FileConfiguration worldConfig;
	
	// basic variables loaded from file
	public String mapName = null;
	public boolean allowFriendlyFire = false;
	
	// task that starts the match
	public MatchStarter matchStarter = null;
	public Set<StartMechanism> startMechanisms = null;

	public AutoRefMatch(World world)
	{
		this.world = world;
		loadWorldConfiguration();
	}
	
	public static boolean isCompatible(World w)
	{ return new File(w.getWorldFolder(), "autoreferee.yml").exists(); }

	public static AutoReferee plugin;
	
	@SuppressWarnings("unchecked")
	private void loadWorldConfiguration()
	{
		// file stream and configuration object (located in world folder)
		worldConfigFile = new File(world.getWorldFolder(), "autoreferee.yml");
		worldConfig = YamlConfiguration.loadConfiguration(worldConfigFile);

		// load up our default values file, so that we can have a base to work with
		InputStream defConfigStream = plugin.getResource("defaults/map.yml");
		if (defConfigStream != null) worldConfig.setDefaults(
			YamlConfiguration.loadConfiguration(defConfigStream));

		// make sure any defaults get copied into the map file
		worldConfig.options().copyDefaults(true);
		worldConfig.options().header(plugin.getDescription().getFullName());
		worldConfig.options().copyHeader(false);

		teams = new HashSet<AutoRefTeam>();
		startMechanisms = new HashSet<StartMechanism>();
		
		for (Map<?, ?> map : worldConfig.getMapList("match.teams"))
			teams.add(AutoRefTeam.fromMap((Map<String, Object>) map, this));
		
		for (String sMech : worldConfig.getStringList("match.start-mechanisms"))
		{
			String[] p = sMech.split(":");
			boolean state = Boolean.parseBoolean(p[1]);
			
			startMechanisms.add(new StartMechanism(world.getBlockAt(
				Vector3.fromCoords(p[0]).toLocation(world)), state));
		}
		
		// get the start region (safe for both teams, no pvp allowed)
		if (worldConfig.isString("match.start-region"))
			startRegion = CuboidRegion.fromCoords(worldConfig.getString("match.start-region"));
		
		// get the time the match is set to start
		if (worldConfig.isString("match.start-time"))
			startTime = AutoReferee.parseTimeString(worldConfig.getString("match.start-time"));
		
		// get the extra settings cached
		mapName = worldConfig.getString("map.name", "<Untitled>");
		allowFriendlyFire = worldConfig.getBoolean("match.allow-ff", false);
	}

	public void saveWorldConfiguration() 
	{
		// if there is no configuration object or file, nothin' doin'...
		if (worldConfigFile == null || worldConfig == null) return;

		// create and save the team data list
		List<Map<String, Object>> teamData = Lists.newArrayList();
		for (AutoRefTeam t : teams) teamData.add(t.toMap());
		worldConfig.set("match.teams", teamData);
		
		// save the start mechanisms
		List<String> sMechs = Lists.newArrayList();
		for ( StartMechanism sMech : startMechanisms ) sMechs.add(sMech.toString());
		worldConfig.set("match.start-mechanisms", sMechs);
		
		// save the start region
		if (startRegion != null)
			worldConfig.set("match.start-region", startRegion.toCoords());

		// save the configuration file back to the original filename
		try { worldConfig.save(worldConfigFile); }

		// log errors, report which world did not save
		catch (java.io.IOException e)
		{ plugin.log.info("Could not save world config: " + world.getName()); }
	}

	public void broadcast(String msg)
	{ for (Player p : world.getPlayers()) p.sendMessage(msg); }

	public static String normalizeMapName(String m)
	{ return m == null ? null : m.toLowerCase().replaceAll("[^0-9a-z]+", ""); }

	public static File getMapFolder(String worldName, Long checksum) throws IOException
	{
		// assume worldName exists
		if (worldName == null) return null;
		worldName = AutoRefMatch.normalizeMapName(worldName);
		
		// if there is no map library, quit
		File mapLibrary = getMapLibrary();
		if (!mapLibrary.exists()) return null;
		
		// find the map being requested
		for (File f : mapLibrary.listFiles())
		{
			// skip non-directories
			if (!f.isDirectory()) continue;
			
			// if it doesn't have an autoreferee config file
			File cfgFile = new File(f, AutoReferee.CFG_FILENAME);
			if (!cfgFile.exists()) continue;
			
			// check the map name, if it matches, this is the one we want
			FileConfiguration cfg = YamlConfiguration.loadConfiguration(cfgFile);
			String cMapName = AutoRefMatch.normalizeMapName(cfg.getString("map.name"));
			if (!worldName.equals(cMapName)) continue;
			
			// compute the checksum of the directory, make sure it matches
			if (checksum != null &&	recursiveCRC32(f) != checksum) continue;
			
			// this is the map we want
			return f;
		}
		
		// no map matches
		return null;
	}

	public static long recursiveCRC32(File file) throws IOException
	{
		if (file.isDirectory())
		{
			long checksum = 0L;
			for (File f : file.listFiles())
				checksum ^= recursiveCRC32(f);
			return checksum;
		}
		else return FileUtils.checksumCRC32(file);
	}

	public static File getMapLibrary()
	{
		// maps library is a folder called `maps/`
		File m = new File("maps");
		
		// if it doesn't exist, make the directory
		if (m.exists() && !m.isDirectory()) m.delete();
		if (!m.exists()) m.mkdir();
		
		// return the maps library
		return m;
	}

	public void destroy() throws IOException
	{
		plugin.matches.remove(world.getUID());
		
		Iterator<Map.Entry<String, AutoRefPlayer>> iterP = 
			plugin.playerData.entrySet().iterator();
		while (iterP.hasNext())
		{
			Map.Entry<String, AutoRefPlayer> e = iterP.next();
			if (world == e.getValue().player.getWorld()) iterP.remove();
		}
		
		Iterator<Map.Entry<String, AutoRefTeam>> iterT = 
			plugin.playerTeam.entrySet().iterator();
		while (iterT.hasNext())
		{
			Map.Entry<String, AutoRefTeam> e = iterT.next();
			if (teams.contains(e.getValue())) iterT.remove();
		}
		
		File worldFolder = world.getWorldFolder();
		plugin.getServer().unloadWorld(world, false);
		
		if (!plugin.getConfig().getBoolean("save-worlds", false))
			FileUtils.deleteDirectory(worldFolder);
	}

	AutoRefTeam getArbitraryTeam()
	{
		// minimum size of any one team, and an array to hold valid teams
		int minsize = Integer.MAX_VALUE;
		List<AutoRefTeam> vteams = Lists.newArrayList();
		
		// get the number of players on each team: Map<TeamNumber -> NumPlayers>
		Map<AutoRefTeam,Integer> count = Maps.newHashMap();
		for (AutoRefTeam t : teams) count.put(t, 0);
		
		for (AutoRefTeam t : plugin.playerTeam.values())
			if (count.containsKey(t)) count.put(t, count.get(t)+1);
		
		// determine the size of the smallest team
		for (Integer c : count.values())
			if (c < minsize) minsize = c.intValue();
	
		// make a list of all teams with this size
		for (Map.Entry<AutoRefTeam,Integer> e : count.entrySet())
			if (e.getValue().intValue() == minsize) vteams.add(e.getKey());
	
		// return a random element from this list
		return vteams.get(new Random().nextInt(vteams.size()));
	}

	class StartMechanism
	{
		public Block block = null;
		public boolean state = true;
		
		public StartMechanism(Block block, boolean state)
		{ this.block = block; this.state = state; }
		
		public StartMechanism(Block block)
		{ this.block = block; this.state = true; }
		
		@Override public int hashCode()
		{ return block.hashCode(); }
		
		@Override public boolean equals(Object o)
		{ return (o instanceof StartMechanism) && 
			block.equals(((StartMechanism)o).block); }
		
		@Override public String toString()
		{ return Vector3.fromLocation(block.getLocation()).toCoords() + 
			":" + Boolean.toString(state); }
	}

	public void addStartMech(Block block, boolean state)
	{
		startMechanisms.add(new StartMechanism(block, state));
		broadcast(block.getState().getType().name() + " @ " + 
			block.getLocation().toString() + " is a start mechanism.");
	}

	public void start()
	{
		currentState = eMatchStatus.PLAYING;
		for (StartMechanism sMech : startMechanisms)
		{
			BlockState blockState = sMech.block.getState();
			MaterialData mdata = blockState.getData();
			
			switch (blockState.getType())
			{
			case LEVER:
				((Lever) mdata).setPowered(sMech.state);
				break;
				
			case STONE_BUTTON:
				((Button) mdata).setPowered(sMech.state);
				break;
				
			case WOOD_PLATE:
			case STONE_PLATE:
				((PressurePlate) mdata).setData((byte) 0x1);
				break;
			}
			
			blockState.setData(mdata);
			blockState.update(true);
		}
	}
}