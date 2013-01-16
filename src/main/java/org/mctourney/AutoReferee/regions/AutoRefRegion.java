package org.mctourney.AutoReferee.regions;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.bukkit.Location;

import org.jdom2.Element;

import org.mctourney.AutoReferee.AutoRefMatch;
import org.mctourney.AutoReferee.AutoRefTeam;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

public abstract class AutoRefRegion
{
	private Random random = new Random();

	public enum Flag
	{
		// - no place/break blocks
		// - no fill/empty buckets
		NO_BUILD(1 << 0, "nobuild", 'b'),

		// - negative region, used for fine tuning access controls
		NO_ENTRY(1 << 1, "noentry", 'n'),

		// - no mob spawns
		// - mobs will not track players in these regions
		SAFE(1 << 2, "safe", 's');

		// value for the flag set
		private int value;

		public int getValue() { return value; }

		// name for use with commands
		private String name;

		public String getName() { return name; }

		// character marker for config files
		private char mark;

		public char getMark() { return mark; }

		Flag(int val, String name, char c)
		{ this.value = val; this.name = name; this.mark = c; }

		public static Flag fromChar(char c)
		{
			for (Flag f : values())
				if (f.mark == c) return f;
			return null;
		}
	}

	private int flags;

	private Set<AutoRefTeam> owners = Sets.newHashSet();

	public AutoRefRegion()
	{ flags = 0; }

	// these methods need to be implemented
	public abstract double distanceToRegion(Location loc);
	public abstract Location getRandomLocation(Random r);
	public abstract CuboidRegion getBoundingCuboid();

	public Location getRandomLocation()
	{ return getRandomLocation(random); }

	public boolean contains(Location loc)
	{ return distanceToRegion(loc) <= 0.0; }

	private boolean is(Flag flag)
	{ return 0 != (flag.getValue() & this.flags); }

	public boolean canBuild()
	{ return !is(Flag.NO_BUILD); }

	public boolean canEnter()
	{ return !is(Flag.NO_ENTRY); }

	public boolean isSafeZone()
	{ return is(Flag.SAFE); }

	public Set<Flag> getFlags()
	{
		Set<Flag> fset = Sets.newHashSet();
		for (Flag f : Flag.values())
			if ((f.getValue() & this.flags) != 0) fset.add(f);
		return fset;
	}

	public AutoRefRegion toggle(Flag flag)
	{ if (flag != null) flags ^= flag.getValue(); return this; }

	public AutoRefRegion addFlags(Element e)
	{
		if (e != null) for (Element c : e.getChildren())
			flags |= Flag.valueOf(c.getName()).getValue();
		return this;
	}

	protected AutoRefRegion getRegionSettings(AutoRefMatch match, Element e)
	{
		this.addFlags(e.getChild("flags"));
		for (Element owner : e.getChildren("owner"))
			this.addOwners(match.teamNameLookup(owner.getTextTrim()));

		return this;
	}

	public Set<AutoRefTeam> getOwners()
	{ return owners; }

	public void addOwners(AutoRefTeam ...teams)
	{ for (AutoRefTeam team : teams) owners.add(team); }

	public boolean isOwner(AutoRefTeam team)
	{ return owners.contains(team); }

	public static CuboidRegion combine(AutoRefRegion reg1, AutoRefRegion reg2)
	{
		// handle nulls gracefully
		if (reg1 == null && reg2 == null) return null;
		if (reg1 == null) return reg2.getBoundingCuboid();
		if (reg2 == null) return reg1.getBoundingCuboid();

		return CuboidRegion.combine(reg1.getBoundingCuboid(), reg2.getBoundingCuboid());
	}

	public static Map<String, Class<? extends AutoRefRegion>> elementNames = Maps.newHashMap();
	static
	{
		elementNames.put("location", PointRegion.class);
		elementNames.put("cuboid", CuboidRegion.class);
	}

	public static void addRegionType(String tag, Class<? extends AutoRefRegion> cls)
	{ elementNames.put(tag, cls); }

	public static AutoRefRegion fromElement(AutoRefMatch match, Element elt)
	{
		Class<? extends AutoRefRegion> cls = elementNames.get(elt.getName());
		if (cls == null) return null;

		try
		{
			Constructor<? extends AutoRefRegion> cons = cls.getConstructor(AutoRefMatch.class, Element.class);
			return cons.newInstance(match, elt).getRegionSettings(match, elt);
		}
		catch (Exception e) { e.printStackTrace(); return null; }
	}
}