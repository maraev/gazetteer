package me.osm.gazetter.striper.builders;

import gnu.trove.list.TLongList;
import gnu.trove.list.array.TLongArrayList;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.osm.gazetter.striper.GeoJsonWriter;
import me.osm.gazetter.striper.builders.handlers.AddrPointHandler;
import me.osm.gazetter.striper.readers.PointsReader.Node;
import me.osm.gazetter.striper.readers.RelationsReader.Relation;
import me.osm.gazetter.striper.readers.RelationsReader.Relation.RelationMember;
import me.osm.gazetter.striper.readers.RelationsReader.Relation.RelationMember.ReferenceType;
import me.osm.gazetter.striper.readers.WaysReader.Way;
import me.osm.gazetter.utils.LocatePoint;
import me.osm.gazetter.utils.binary.Accessor;
import me.osm.gazetter.utils.binary.Accessors;
import me.osm.gazetter.utils.binary.BinaryBuffer;
import me.osm.gazetter.utils.binary.ByteBufferList;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;

public class AddrPointsBuilder extends ABuilder {
	
	private static final Logger log = LoggerFactory.getLogger(AddrPointsBuilder.class.getName());
	
	private static final String ADDR_STREET = "addr:street";

	private static final String ADDR_INTERPOLATION = "addr:interpolation";
	private static final String ADDR_HOUSENUMBER = "addr:housenumber";

	private BinaryBuffer way2relation = new ByteBufferList(8 + 8); 
	private BinaryBuffer node2way = new ByteBufferList(8 + 8 + 2 + 8 + 8);
	private BinaryBuffer nodeInterpolation = new ByteBufferList(8 + 2 + 8);
	
	private Map<Long, String> interpolation2Street = new HashMap<>();
	
	//Trying to save some memory
	private TLongList writedAddrNodes = new TLongArrayList(); 
	
	private boolean indexFilled = false;
	private boolean orderedByway = false;
	private AddrPointHandler handler; 
	
	private GeometryFactory factory = new GeometryFactory();
	private boolean byRealtionOrdered = false;
	
	public AddrPointsBuilder (AddrPointHandler handler) {
		this.handler = handler;
	}
	
	private static final boolean fullGeometry = true;
	private static final long MASK_16_BITS = 0xFFFFL;

	private static final Accessor w2rRelAccessor = Accessors.longAccessor(0);
	private static final Accessor niNodeAccessor = Accessors.longAccessor(0);
	private static final Accessor n2wNodeAccessor = Accessors.longAccessor(0);
	private static final Accessor way2RelRelIdAccessor = Accessors.longAccessor(8);
	private static final Accessor n2wWayAccessor = Accessors.longAccessor(8);
	private static final Accessor inplnNodeAccessor = Accessors.longAccessor(0);
	private static final Accessor n2wLineAccessor = Accessors.longAccessor(8);
	
	@Override
	public void handle(final Relation rel) {
		if(!indexFilled) {
			if(hasAddr(rel.tags)) {
				indexRelation(rel);
			}
		}
		else {
			orderByRelation();
			orderByWay();
			
			buildAddrPoint4Relation(rel);
		}
	}

	private void buildAddrPoint4Relation(final Relation rel) {
		int i = way2relation.find(rel.id, way2RelRelIdAccessor);

		if(i < 0) {
			return;
		}
		
		Point centroid = null;
		List<LineString> lines = new ArrayList<>();
		
		for(ByteBuffer bb : way2relation.findAll(i, rel.id, way2RelRelIdAccessor)) {
			final long way = bb.getLong(0);
			
			int p = node2way.find(way, n2wWayAccessor);

			if(fullGeometry) {
				List<ByteBuffer> wayPoints = getWayPoints(way);
				Collections.sort(wayPoints, new Comparator<ByteBuffer>() {
					@Override
					public int compare(ByteBuffer o1, ByteBuffer o2) {
						return Short.compare(o1.getShort(8 + 8), o2.getShort(8 + 8));
					}
				});
				List<Coordinate> coords = new ArrayList<>();
				for(ByteBuffer pbb : wayPoints) {
					double lon = pbb.getDouble(8 + 8 + 2);
					double lat = pbb.getDouble(8 + 8 + 2 + 8);
					coords.add(new Coordinate(lon, lat));
				}

				if(coords.isEmpty()) {
					log.error("Failed to build geometry for relation {}. No points found.", rel.id);
					return;
				}
				
				LineString l = factory.createLineString(coords.toArray(new Coordinate[coords.size()]));
				lines.add(l);
			}
			else {
				for(ByteBuffer bb2 : node2way.findAll(p, way, n2wWayAccessor)) {
					double lon = bb2.getDouble(8 + 8 + 2);
					double lat = bb2.getDouble(8 + 8 + 2 + 8);
					centroid = factory.createPoint(new Coordinate(lon, lat));
				}
				break;
			}
		}

		JSONObject meta = new JSONObject();
		meta.put("id", rel.id);
		meta.put("type", "relation");
		
		if(fullGeometry) {
			if(lines.isEmpty()) {
				return;
			}
			
			MultiPolygon mp = BuildUtils.buildMultyPolygon(rel, lines, null);
			if(mp != null && !mp.isEmpty() && mp.isValid()) {
				centroid = mp.getCentroid();
				
				Polygon polygon = (Polygon) mp.getGeometryN(0);
				meta.put(GeoJsonWriter.FULL_GEOMETRY, GeoJsonWriter.geometryToJSON(polygon));
			}
			else {
				centroid = lines.get(0).getCentroid();
			}
		}
		
		handler.handleAddrPoint(rel.tags, centroid, meta);
	}

	private void orderByRelation() {
		if(!this.byRealtionOrdered) {
			way2relation.sort(Builder.SECOND_LONG_FIELD_COMPARATOR);
			this.byRealtionOrdered = true;
		}
	}

	private void indexRelation(Relation rel) {
		for (RelationMember rm : rel.members) {
			if(rm.type == ReferenceType.WAY && (StringUtils.isEmpty(rm.role) || "outer".equals(rm.role))) {
				ByteBuffer bb = ByteBuffer.allocate(8 + 8);
				bb.putLong(rm.ref).putLong(rel.id);
				way2relation.add(bb);
				
				if(!fullGeometry) {
					//one way (one point) will be enough
					break;
				} 
			}
		}
	}

	@Override
	public void firstRunDoneRelations() {
		handler.newThreadpoolUser(getThreadPoolUser());
		way2relation.sort(Builder.FIRST_LONG_FIELD_COMPARATOR);
	}
	
	@Override
	public void handle(final Way line) {
		if (!indexFilled) {
			indexWay(line);
		} else {
			orderByWay();
			if (line.isClosed() && hasAddr(line.tags)) {
				buildAddrPointForWay(line);
			} 
			else if (isInterpolation(line.tags)) {
				buildAddrPoints4Interpolation(line);
			} 
			
			if (line.isClosed() && line.tags.containsKey("building")) {
				for(int i = 0; i < line.nodes.size() - 1; i++) {
					long nid = line.nodes.get(i);
					int nodeIndex = binarySearchWithMask(writedAddrNodes, nid);
					if (nodeIndex >= 0) {
						long addrNodeIdWithN = writedAddrNodes.get(nodeIndex);
						long n = addrNodeIdWithN & MASK_16_BITS;
						long addrNodeId = addrNodeIdWithN >> 16;
						this.handler.handleAddrPoint2Building(
								String.format("%04d", n), addrNodeId, line.id,
								line.tags);
					}
				}
			}
		}
	}
	
	
	private void buildAddrPoints4Interpolation(final Way line) {
		String interpolation = line.tags.get(ADDR_INTERPOLATION);
		int step = getInterpolationStep(interpolation);
		
		if(step > 0) {
			List<ByteBuffer> points = getWayPoints(line.id);

			long prevPID = -1;
			short prevHN = -1;
			int counter = 0;

			List<double[]> coords = new ArrayList<>();
			
			if(points.size() > 1) {
				for(ByteBuffer bb : points) {
					
					long pid = bb.getLong(0);
					double lon = bb.getDouble(8 + 8 + 2);
					double lat = bb.getDouble(8 + 8 + 2 + 8);
					
					if(pid != prevPID) {
						short hn = getInterpolationPointHN(pid);
						
						coords.add(new double[]{lon, lat});
						
						if(hn > 0 && coords.size() > 1) {
							counter = interpolateSegment(step, prevHN, hn, coords, line, pid, prevPID, counter);
							coords.clear();
							coords.add(new double[]{lon, lat});
						}
						
						if (hn <= 0 && line.nodes.get(0).equals(pid)) {
							log.warn("Broken interpolation at point {}. First point has no recognizeable addr:housenumber", pid);
						}
						
						prevPID = pid;
						prevHN = hn > 0 ? hn : prevHN;
					}
				}
				
				if(coords.size() > 1) {
					log.warn("Broken interpolation at point {}. Last point has no recognizeable addr:housenumber", prevPID);
				}
			}
		}
		else {
			log.warn("Unsupported interpolation type: {}", interpolation);
		}
		
	}

	private int interpolateSegment(int s, short prevHN, short hn,
			List<double[]> coords, Way way, long pid, long prevPID, int counter) {
		
		int from = Math.min(prevHN, hn);
		int to = Math.max(prevHN, hn);
		int step = Math.abs(s);
		
		int a = 0;
		Coordinate[] coordinates = new Coordinate[coords.size()];
		for(double[] d : coords) {
			coordinates[a++] = new Coordinate(d[0], d[1]);
		}
		LineString ls = factory.createLineString(coordinates);
		double length = ls.getLength();
		int steps = (to - from) / step;
		double dl = length / steps;
		
		for(int i = from, stepN = 0; i <= to; i += step, stepN++) {

			double l = stepN * dl;
			Coordinate c = new LocatePoint(ls, l).getPoint();
			
			JSONObject meta = new JSONObject();
			
			meta.put("id", way.id);
			meta.put("type", "interpolation");
			
			//such points will be duplicated by simple nodes, so mark it.
			if(i == from) {
				meta.put("firstInInterpolation", true);
				meta.put("basePointid", pid);
			}
			if(i == to) {
				meta.put("lastInInterpolation", true);
				meta.put("basePointid", prevPID);
			}
			
			if(way.tags.get(ADDR_STREET) == null && interpolation2Street.get(way.id) != null){
				way.tags.put(ADDR_STREET, interpolation2Street.get(way.id));
			}
			
			way.tags.put(ADDR_HOUSENUMBER, String.valueOf(i));
			
			meta.put("counter", counter++);
			handler.handleAddrPoint(way.tags, factory.createPoint(c), meta);
		}
		
		return counter;
		
	}

	private short getInterpolationPointHN(final long id) {
		int i = nodeInterpolation.find(id, inplnNodeAccessor);

		if(i >= 0) {
			return nodeInterpolation.get(i).getShort(8);
		}
		
		return -1;
	}

	private int getInterpolationStep(String interpolation) {

		if("all".equalsIgnoreCase(interpolation)) {
			return 1;
		}

		if("even".equalsIgnoreCase(interpolation) || "odd".equalsIgnoreCase(interpolation)) {
			return 2;
		}
		
		try {
			return Integer.parseInt(interpolation);
		}
		catch (Exception e) {
			
		}
		
		return -1;
	}

	private List<ByteBuffer> getWayPoints(final long lineId) {
		int i = node2way.find(lineId, n2wLineAccessor);

		List<ByteBuffer> points = node2way.findAll(i, lineId, n2wLineAccessor);
		Collections.sort(points, new Comparator<ByteBuffer>() {

			@Override
			public int compare(ByteBuffer o1, ByteBuffer o2) {
				return Short.compare(o1.getShort(8 + 8), o2.getShort(8 + 8));
			}
			
		});
		return points;
	}

	private void buildAddrPointForWay(final Way line) {
		int i = node2way.find(line.id, n2wLineAccessor);
		if(i >= 0) {
			JSONObject meta = new JSONObject();
			meta.put("id", line.id);
			meta.put("type", "way");

			Point centroid = null;
			if(fullGeometry) {
				List<ByteBuffer> wayPoints = getWayPoints(line.id);
				Collections.sort(wayPoints, new Comparator<ByteBuffer>() {
					@Override
					public int compare(ByteBuffer o1, ByteBuffer o2) {
						return Short.compare(o1.getShort(8 + 8), o2.getShort(8 + 8));
					}
				});
				List<Coordinate> coords = new ArrayList<>();
				for(ByteBuffer bb : wayPoints) {
					double lon = bb.getDouble(8 + 8 + 2);
					double lat = bb.getDouble(8 + 8 + 2 + 8);
					coords.add(new Coordinate(lon, lat));
				}
				
				if(coords.isEmpty()) {
					log.error("Failed to build geometry for way {}. No points found.", line.id);
					return;
				}
				
				if(coords.size() != line.nodes.size()) {
					log.warn("Failed to build geometry for way {}. Some points wasn't found.", line.id);
					centroid = factory.createPoint(coords.get(0));
				}
				else if(coords.size() < 4) {
					log.warn("Wrong number of points for {}", line.id);
					centroid = factory.createPoint(coords.get(0));
				}
				else {
					LinearRing geom = factory.createLinearRing(coords.toArray(new Coordinate[coords.size()]));
					centroid = geom.getCentroid();
					Polygon p = factory.createPolygon(geom);
					if(p.isValid()) {
						meta.put("fullGeometry", GeoJsonWriter.geometryToJSON(p));
					}
				}
				
			}
			else {
				ByteBuffer bb = node2way.get(i);
				double lon = bb.getDouble(8 + 8 + 2);
				double lat = bb.getDouble(8 + 8 + 2 + 8);
				centroid = factory.createPoint(new Coordinate(lon, lat));
			}
			
			handler.handleAddrPoint(line.tags, centroid, meta);
		}
	}

	private void orderByWay() {
		if(!this.orderedByway) {
			node2way.sort(Builder.SECOND_LONG_FIELD_COMPARATOR);
			this.orderedByway = true;
		}
	}

	private void indexWay(Way line) {
		if(line.isClosed() && hasAddr(line.tags)) {
			indexLine(line);
		}
		else if (isInterpolation(line.tags)) {
			short i = 1;
			for(long p : line.nodes) {
				ByteBuffer bb = ByteBuffer.allocate(8 + 8 + 2 + 8 + 8);
				bb.putLong(p).putLong(line.id).putShort(i++);
				node2way.add(bb);
				
				bb = ByteBuffer.allocate(8 + 2 + 8);
				bb.putLong(0, p);
				bb.putLong(8 + 2, line.id);
				nodeInterpolation.add(bb);
			}
		}
		else if (way2relation.find(line.id, w2rRelAccessor) >= 0) {
			indexLine(line);
		}
	}

	private void indexLine(Way line) {
		short i = 0;
		for(long ln :line.nodes) {
			ByteBuffer bb = ByteBuffer.allocate(8 + 8 + 2 + 8 + 8);
			bb.putLong(ln).putLong(line.id).putShort(i++);
			node2way.add(bb);
			
			if(!fullGeometry) {
				break;
			}
		}
	}

	@Override
	public void firstRunDoneWays() {
		node2way.sort(Builder.FIRST_LONG_FIELD_COMPARATOR);
		nodeInterpolation.sort(Builder.FIRST_LONG_FIELD_COMPARATOR);
		log.info("Done read ways. {} nodes added to index.", node2way.size());
		this.indexFilled = true;
	}

	@Override
	public void handle(final Node node) {
		
		if(hasAddr(node.tags)) {
			JSONObject meta = new JSONObject();
			
			meta.put("id", node.id);
			meta.put("type", "node");
			Point point = factory.createPoint(new Coordinate(node.lon, node.lat));
			handler.handleAddrPoint(node.tags, point, meta);
			
			short n = new Double((point.getX() + 180.0) * 10.0).shortValue();
			
			long nodeWithN = node.id;
			nodeWithN <<= 16;
			nodeWithN |= n;
			
			writedAddrNodes.add(nodeWithN);
		}
		
		indexNode2Way(node);
		
		indexNodeInterpolation(node);
		
	}
	
	@Override
	public void firstRunDoneNodes() {
		writedAddrNodes.sort();
	}

	private void indexNodeInterpolation(final Node node) {
		if(hasAddr(node.tags)) {
			int ni = nodeInterpolation.find(node.id, niNodeAccessor);
			
			for(ByteBuffer bb : nodeInterpolation.findAll(ni, node.id, niNodeAccessor)) {
				bb.putShort(8, getHN(node.tags));
				
				String street = node.tags.get(ADDR_STREET);
				if(street != null) {
					long intWayId = bb.getLong(8 + 2);
					if(interpolation2Street.get(intWayId) == null) {
						interpolation2Street.put(intWayId, street);
					}
					else if(!interpolation2Street.get(intWayId).equals(street)) {
						log.warn("Different streets on addr interpolated nodes. "
								+ "Interpolation way id: {} street: {} ({}) Node: {}", 
								new Object[]{intWayId, street, interpolation2Street.get(intWayId), node.id});
					}
				}
			}
		}
		
	}

	private short getHN(Map<String, String> tags) {
		try {
			String hn = tags.get(ADDR_HOUSENUMBER);
			if(StringUtils.isNumericSpace(hn)) {
				return Short.valueOf(StringUtils.trim(hn));
			}
			else if(StringUtils.isNumericSpace(hn.substring(0, hn.length() - 2))) {
				return Short.valueOf(hn.substring(0, hn.length() - 2));
			}
		}
		catch (Exception e) {
		}
		return -1;
	}

	private void indexNode2Way(final Node node) {
		int ni = node2way.find(node.id, n2wNodeAccessor);
		
		for(ByteBuffer bb : node2way.findAll(ni, node.id, n2wNodeAccessor)) {
			bb.putDouble(8 + 8 + 2, node.lon);
			bb.putDouble(8 + 8 + 2 + 8, node.lat);
		}
	}
	
	private static boolean isInterpolation(Map<String, String> tags) {
		return tags.containsKey(ADDR_INTERPOLATION);
	}

	private static boolean hasAddr(Map<String, String> tags) {
		return tags.containsKey(ADDR_HOUSENUMBER);
	}

	@Override
	public void secondRunDoneRelations() {
		handler.freeThreadPool(getThreadPoolUser());
	}
	
}