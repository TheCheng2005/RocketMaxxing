package io.github.openrocketmcp.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.motor.ThrustCurveMotor;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.DeploymentConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurableComponent;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.MotorMount;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.rocketcomponent.StageSeparationConfiguration;
import io.github.openrocketmcp.mcp.Args;
import io.github.openrocketmcp.mcp.Log;
import io.github.openrocketmcp.mcp.ToolException;
import io.github.openrocketmcp.or.Components;
import io.github.openrocketmcp.or.Designs;
import io.github.openrocketmcp.or.Motors;
import io.github.openrocketmcp.tools.Context;

/**
 * The Motors page's tools: flight configurations, each mount's motor and its delays, the motor database, and the
 * team's own motor files. Registered by {@link AppTools}.
 */
final class AppMotors {
	private AppMotors() {
	}

	// ------------------------------------------------------------------------------------------- motors and configuration

	static Map<String, Object> configs(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("configurations", AppDesign.configList(r));
		out.put("selected", r.getSelectedConfiguration().getId().toString());
		List<Map<String, Object>> mounts = new ArrayList<>(), recovery = new ArrayList<>(), stages = new ArrayList<>();
		for (RocketComponent c : r) {
			if (c instanceof MotorMount mm && mm.isMotorMount()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", Components.shortId(c));
				m.put("name", c.getName());
				m.put("stage", c.getStage().getName());
				m.put("innerDiameter", mm.getMotorMountDiameter());
				Map<String, Object> per = new LinkedHashMap<>();
				for (FlightConfigurationId id : r.getIds()) {
					per.put(id.toString(), motor(mm.getMotorConfig(id)));
				}
				m.put("motors", per);
				mounts.add(m);
			}
			if (c instanceof RecoveryDevice rd) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", Components.shortId(c));
				m.put("name", c.getName());
				m.put("type", c.getClass().getSimpleName());
				Map<String, Object> per = new LinkedHashMap<>();
				for (FlightConfigurationId id : r.getIds()) {
					DeploymentConfiguration dc = rd.getDeploymentConfigurations().get(id);
					per.put(id.toString(), Map.of("event", dc.getDeployEvent().name(), "altitude", dc.getDeployAltitude(), "delay",
							dc.getDeployDelay()));
				}
				m.put("deployment", per);
				recovery.add(m);
			}
			if (c instanceof AxialStage st) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("id", Components.shortId(c));
				m.put("name", c.getName());
				m.put("stageNumber", st.getStageNumber());
				Map<String, Object> per = new LinkedHashMap<>();
				for (FlightConfigurationId id : r.getIds()) {
					StageSeparationConfiguration sc = st.getSeparationConfigurations().get(id);
					per.put(id.toString(), Map.of("event", sc.getSeparationEvent().name(), "delay", sc.getSeparationDelay()));
				}
				m.put("separation", per);
				stages.add(m);
			}
		}
		out.put("mounts", mounts);
		out.put("recovery", recovery);
		out.put("stages", stages);
		return out;
	}

	static Map<String, Object> motor(MotorConfiguration mc) {
		Map<String, Object> m = new LinkedHashMap<>();
		Motor mo = mc.getMotor();
		if (mo == null) {
			return m;
		}
		m.put("designation", mo.getDesignation());
		m.put("manufacturer", mo instanceof ThrustCurveMotor t ? t.getManufacturer().getDisplayName() : "");
		m.put("totalImpulse", mo.getTotalImpulseEstimate());
		m.put("averageThrust", mo.getAverageThrustEstimate());
		m.put("burnTime", mo.getBurnTimeEstimate());
		m.put("diameter", mo.getDiameter());
		m.put("length", mo.getLength());
		m.put("ejectionDelay", mc.getEjectionDelay());
		m.put("plugged", mc.getEjectionDelay() == Motor.PLUGGED_DELAY);
		m.put("ignitionEvent", mc.getIgnitionEvent().name());
		m.put("ignitionDelay", mc.getIgnitionDelay());
		if (mo instanceof ThrustCurveMotor t) {
			m.put("delays", t.getStandardDelays());
		}
		return m;
	}

	static Map<String, Object> motorDelay(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		RocketComponent c = Components.find(r, a.str("mount"));
		if (!(c instanceof MotorMount mm) || !mm.isMotorMount()) {
			throw new ToolException(c.getName() + " is not a motor mount.");
		}
		FlightConfigurationId id = Components.config(r, a.str("configuration")).getId();
		MotorConfiguration mc = mm.getMotorConfig(id);
		if (a.bool("remove", false)) {
			mm.getMotorConfigurationSet().set(id, new MotorConfiguration(mm, id));
		} else {
			if (mc.getMotor() == null) {
				throw new ToolException("No motor in " + c.getName() + " for this configuration.");
			}
			if (a.bool("plugged", false)) {
				mc.setEjectionDelay(Motor.PLUGGED_DELAY);
			} else if (a.has("ejectionDelay")) {
				mc.setEjectionDelay(a.num("ejectionDelay"));
			}
			if (a.has("ignitionEvent")) {
				mc.setIgnitionEvent(info.openrocket.core.motor.IgnitionEvent.valueOf(a.str("ignitionEvent")));
			}
			if (a.has("ignitionDelay")) {
				mc.setIgnitionDelay(a.num("ignitionDelay"));
			}
		}
		r.fireComponentChangeEvent(info.openrocket.core.rocketcomponent.ComponentChangeEvent.MOTOR_CHANGE);
		d.doc.setSaved(false);
		return motor(mm.getMotorConfig(id));
	}

	static Map<String, Object> config(Context ctx, Args a) {
		Designs.Design d = ctx.designs.get(a.str("designId"));
		Rocket r = d.doc.getRocket();
		FlightConfiguration fc = Components.config(r, a.str("configuration"));
		FlightConfigurationId old = fc.getId();
		if (a.str("action").equals("remove")) {
			if (r.getIds().size() <= 1) {
				throw new ToolException("A design keeps at least one flight configuration.");
			}
			r.removeFlightConfiguration(old);
			d.doc.setSaved(false);
			return Map.of("removed", fc.getName(), "selected", r.getSelectedConfiguration().getId().toString());
		}
		FlightConfigurationId id = new FlightConfigurationId();
		FlightConfiguration copy = fc.copy(id);
		r.setFlightConfiguration(id, copy);
		for (RocketComponent c : r) {
			if (c instanceof FlightConfigurableComponent fcc) {
				fcc.copyFlightConfiguration(old, id);
			}
		}
		if (fc.isNameOverridden()) {
			copy.setName(fc.getNameRaw() + " (copy)");
		}
		r.setSelectedConfiguration(id);
		d.doc.setSaved(false);
		return Map.of("id", id.toString(), "name", copy.getName());
	}

	static Map<String, Object> motors(Context ctx, Args a) {
		double minD = Double.NaN, maxD = Double.NaN;
		if (a.has("mount") && a.has("designId")) {
			RocketComponent c = Components.find(ctx.designs.get(a.str("designId")).doc.getRocket(), a.str("mount"));
			if (c instanceof MotorMount mm) {
				maxD = mm.getMotorMountDiameter() + 1e-4;
				minD = mm.getMotorMountDiameter() - 1e-3;
			}
		}
		String text = a.str("text", "").trim();
		List<ThrustCurveMotor> all = Motors.search(new Motors.Filter(minD, maxD, Double.NaN, a.str("minClass", null),
				a.str("maxClass", null), null, null, null, a.bool("availableOnly", false)));
		List<Map<String, Object>> out = new ArrayList<>();
		int limit = Math.max(1, a.integer("limit", 300)), matches = 0;
		String t = text.toLowerCase(java.util.Locale.ROOT);
		java.util.Set<String> seen = new java.util.HashSet<>();
		for (ThrustCurveMotor m : all) {
			// The same motor from two data files: same digest, or the same maker, designation and numbers.
			String same = m.getManufacturer().getDisplayName() + "|" + m.getDesignation() + "|"
					+ Math.round(m.getTotalImpulseEstimate()) + "|" + Math.round(m.getBurnTimeEstimate() * 100);
			if (!seen.add(m.getDigest()) || !seen.add(same)) {
				continue;
			}
			String hay = (m.getManufacturer().getDisplayName() + " " + m.getManufacturer().getSimpleName() + " " + m.getDesignation()
					+ " " + m.getCommonName()).toLowerCase(java.util.Locale.ROOT);
			if (!t.isEmpty() && !java.util.Arrays.stream(t.split("\\s+")).allMatch(hay::contains)) {
				continue;
			}
			matches++;
			if (out.size() < limit) {
				out.add(motorInfo(m));
			}
		}
		return Map.of("matches", matches, "motors", out);
	}

	/** The designs folder's motors/: motor files the team imported and motors made from its own test data. */
	static Path motorsDir(Context ctx) {
		return (ctx.workspace() != null ? ctx.workspace() : Path.of("").toAbsolutePath()).resolve("motors");
	}

	static volatile Path teamMotorsLoaded;

	/**
	 * Loads the team's motors once, when the page first connects (before a design that uses one is opened), so a design
	 * keeps its own motor from one session to the next.
	 */
	static synchronized void teamMotors(Context ctx) {
		Path dir = motorsDir(ctx);
		if (dir.equals(teamMotorsLoaded) || !Files.isDirectory(dir)) {
			return;
		}
		teamMotorsLoaded = dir;
		try (var files = Files.list(dir)) {
			for (Path f : files.sorted().toList()) {
				String n = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
				if (n.endsWith(".eng") || n.endsWith(".rse") || n.endsWith(".zip")) {
					try {
						Motors.importFile(f);
					} catch (IOException | RuntimeException e) {
						io.github.openrocketmcp.mcp.Log.info("Skipped motor file " + f + ": " + e.getMessage());
					}
				}
			}
		} catch (IOException e) {
			io.github.openrocketmcp.mcp.Log.info("Could not read " + dir + ": " + e.getMessage());
		}
	}

	/** Imports a motor file and keeps a copy in the designs folder's motors/, so it is there next time. */
	static Map<String, Object> importMotor(Context ctx, String path) throws IOException {
		Path src = ctx.path(path).toAbsolutePath().normalize();
		List<ThrustCurveMotor> found = Motors.importFile(src); // checks the file before it is kept
		Path dir = motorsDir(ctx);
		Path kept = src;
		if (!src.startsWith(dir)) {
			Files.createDirectories(dir);
			String name = src.getFileName().toString();
			kept = dir.resolve(name);
			for (int i = 2; Files.exists(kept) && Files.mismatch(kept, src) != -1; i++) {
				int dot = name.lastIndexOf('.');
				kept = dir.resolve(name.substring(0, dot) + "-" + i + name.substring(dot));
			}
			Files.copy(src, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		List<Map<String, Object>> motors = new ArrayList<>();
		for (ThrustCurveMotor m : found) {
			motors.add(motorInfo(m));
		}
		return Map.of("file", kept.toString(), "motors", motors);
	}

	static Map<String, Object> motorInfo(ThrustCurveMotor m) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("digest", m.getDigest());
		o.put("designation", m.getDesignation());
		o.put("commonName", m.getCommonName());
		o.put("manufacturer", m.getManufacturer().getDisplayName());
		o.put("class", Motors.impulseClass(m.getTotalImpulseEstimate()));
		o.put("diameter", m.getDiameter());
		o.put("length", m.getLength());
		o.put("totalImpulse", m.getTotalImpulseEstimate());
		o.put("averageThrust", m.getAverageThrustEstimate());
		o.put("maxThrust", m.getMaxThrustEstimate());
		o.put("burnTime", m.getBurnTimeEstimate());
		o.put("launchMass", m.getLaunchMass());
		o.put("propellantMass", m.getPropellantMass());
		o.put("type", m.getMotorType().name());
		o.put("delays", m.getStandardDelays());
		o.put("available", m.isAvailable());
		return o;
	}
}
