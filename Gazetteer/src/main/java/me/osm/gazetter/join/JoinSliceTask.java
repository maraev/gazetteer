package me.osm.gazetter.join;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import me.osm.gazetter.Options;
import me.osm.gazetter.addresses.AddrLevelsComparator;
import me.osm.gazetter.addresses.AddrLevelsSorting;
import me.osm.gazetter.addresses.AddressesParser;
import me.osm.gazetter.addresses.AddressesUtils;
import me.osm.gazetter.addresses.NamesMatcher;
import me.osm.gazetter.addresses.sorters.CityStreetHNComparator;
import me.osm.gazetter.addresses.sorters.HNStreetCityComparator;
import me.osm.gazetter.addresses.sorters.StreetHNCityComparator;
import me.osm.gazetter.join.PoiAddrJoinBuilder.BestFitAddresses;
import me.osm.gazetter.striper.FeatureTypes;
import me.osm.gazetter.striper.GeoJsonWriter;
import me.osm.gazetter.striper.JSONFeature;
import me.osm.gazetter.utils.FileUtils;
import me.osm.gazetter.utils.FileUtils.LineHandler;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.index.quadtree.Quadtree;
import com.vividsolutions.jts.operation.buffer.BufferOp;

public class JoinSliceTask implements Runnable {
	
	private static final Logger log = LoggerFactory.getLogger(JoinSliceTask.class);
	
	private static final double STREET_BUFFER_DISTANCE = 1.0 / 111195.0 * 250;
	private static final double POI_BUFFER_DISTANCE = 1.0 / 111195.0 * 100;
	
	private File src;
	
	private final List<JSONObject> addrPoints = new ArrayList<>();
	private final Quadtree addrPointsIndex = new Quadtree();
	private final Quadtree streetsPointsIndex = new Quadtree();
	private final Quadtree placesPointsIndex = new Quadtree();
	
	private final List<JSONObject> boundaries = new ArrayList<>();
	private final List<JSONObject> places = new ArrayList<>();
	private final List<JSONObject> placesVoronoi = new ArrayList<>();
	private final List<JSONObject> neighboursVoronoi = new ArrayList<>();
	private final List<JSONObject> streets = new ArrayList<>();
	private final List<JSONObject> junctions = new ArrayList<>();
	private final List<JSONObject> associatedStreets = new ArrayList<>();
	
	private final List<JSONObject> poi2bdng = new ArrayList<>();
	private final List<JSONObject> addr2bdng = new ArrayList<>();

	private final List<JSONObject> pois = new ArrayList<>();
	private final Quadtree poisIndex = new Quadtree();

	private AddrJointHandler handler;
	private List<JSONObject> common;
	
	//dependancies
	private final PoiAddrJoinBuilder poiAddrJoinBuilder = new PoiAddrJoinBuilder();
	private final AddressesParser addressesParser = Options.get().getAddressesParser();
	private final NamesMatcher namesMatcher = Options.get().getNamesMatcher();
	private final AddrLevelsComparator addrLevelComparator;
	
	
	private static final GeometryFactory factory = new GeometryFactory();
		
	private AtomicInteger stripesCounter;

	private Set<String> necesaryBoundaries;

	private Joiner joiner;

	
	public JoinSliceTask(AddrJointHandler handler, File src, 
			List<JSONObject> common, Set<String> filter, Joiner joiner) {
		
		this.src = src;
		this.handler = handler;
		this.common = common;
		this.necesaryBoundaries = filter;
		this.joiner = joiner;
		
		if(log.isTraceEnabled()) {
			this.stripesCounter = joiner.getStripesCounter();
		}
		
		AddrLevelsSorting sorting = Options.get().getSorting();
		if(AddrLevelsSorting.HN_STREET_CITY == sorting) {
			addrLevelComparator = new HNStreetCityComparator();
		}
		else if (AddrLevelsSorting.CITY_STREET_HN == sorting) {
			addrLevelComparator = new CityStreetHNComparator();
		}
		else {
			addrLevelComparator = new StreetHNCityComparator();
		}
	}
	
	private static final ByIdComparator BY_ID_COMPARATOR = new ByIdComparator();

	private static final class ByIdComparator implements Comparator<JSONObject>{
		@Override
		public int compare(JSONObject arg0, JSONObject arg1) {
			String id0 = arg0.optString("id");
			String id1 = arg1.optString("id");
			return id0.compareTo(id1);
		}
	} 
	
	private Map<JSONObject, List<JSONObject>> addr2streets; 
	private Map<JSONObject, List<JSONObject>> addr2bndries; 
	private Map<JSONObject, List<JSONObject>> place2bndries; 

	private Map<JSONObject, List<List<JSONObject>>> street2bndries; 
	
	private Map<JSONObject, JSONObject> addr2PlaceVoronoy; 
	private Map<JSONObject, JSONObject> addr2NeighbourVoronoy ; 
	
	private Map<Long, Set<JSONObject>> street2Junctions; 
	
	private Map<Long, JSONObject> poiPnt2Builng;
	private Map<Long, JSONObject> addrPnt2Builng;

	private Map<JSONObject, List<JSONObject>> poi2bndries;
	private Map<String, JSONObject> addrPnt2AsStreet; 

	@Override
	public void run() {
		
		Thread.currentThread().setName("join-" + this.src.getName());
		
		try {
			
			readFeatures();
			
			initializeMaps();
			
			Collections.sort(addrPoints, BY_ID_COMPARATOR);
			for(JSONObject point : addrPoints) {
				JSONArray ca = point.getJSONObject(GeoJsonWriter.GEOMETRY).getJSONArray(GeoJsonWriter.COORDINATES);
				addrPointsIndex.insert(new Envelope(new Coordinate(ca.getDouble(0), ca.getDouble(1))), point);
			}

			Collections.sort(boundaries, BY_ID_COMPARATOR);
			
			for(JSONObject street : streets) {
				JSONArray ca = street.getJSONObject(GeoJsonWriter.GEOMETRY).getJSONArray(GeoJsonWriter.COORDINATES);
				for(int i =0; i < ca.length(); i++) {
					JSONArray p = ca.getJSONArray(i);
					Coordinate coordinate = new Coordinate(p.getDouble(0), p.getDouble(1));
					streetsPointsIndex.insert(new Envelope(coordinate), new Object[]{coordinate, street});
				}
			}
			
			
			for(JSONObject point : pois) {
				JSONArray ca = point.getJSONObject(GeoJsonWriter.GEOMETRY).getJSONArray(GeoJsonWriter.COORDINATES);
				poisIndex.insert(new Envelope(new Coordinate(ca.getDouble(0), ca.getDouble(1))), point);
			}

			mergePois();
			
			for(JSONObject point : places) {
				JSONArray ca = point.getJSONObject(GeoJsonWriter.GEOMETRY).getJSONArray(GeoJsonWriter.COORDINATES);
				placesPointsIndex.insert(new Envelope(new Coordinate(ca.getDouble(0), ca.getDouble(1))), point);
			}
			
			for(JSONObject obj : poi2bdng) {
				poiPnt2Builng.put(obj.getLong("nodeId"), obj);
			}
			
			for(JSONObject obj : addr2bdng) {
				addrPnt2Builng.put(obj.getLong("nodeId"), obj);
			}
			
			join();
			
			write();

			if(log.isTraceEnabled()) {
				log.trace("Done. {} left", this.stripesCounter.decrementAndGet());
			}
		}
		catch (Exception e) {
			log.error("Join failed. File: {}.", this.src);
			throw new RuntimeException("Failed to join " + this.src, e);
		}
		
	}

	@SuppressWarnings("unchecked")
	private void mergePois() {
		for(JSONObject poi : pois) {
			JSONObject meta = poi.getJSONObject(GeoJsonWriter.META);
			JSONObject fullGeometry = meta.optJSONObject("fullGeometry");
			if(fullGeometry != null && "Polygon".equals(fullGeometry.getString("type"))) {
				Polygon poly = GeoJsonWriter.getPolygonGeometry(
						fullGeometry.getJSONArray(GeoJsonWriter.COORDINATES));
				
				List<JSONObject> dubles = (List<JSONObject>)poisIndex.query(poly.getEnvelopeInternal());
				if(dubles.size() > 1) {
					//remove self
					dubles.remove(poi);
					
					filterMatchedPois(poi, dubles);
					JSONObject poiProperties = poi.getJSONObject(GeoJsonWriter.PROPERTIES);
					
					Iterator<JSONObject> iterator = dubles.iterator();
					while(iterator.hasNext()) {
						JSONObject matched = iterator.next();
						JSONArray coords = matched.getJSONObject(GeoJsonWriter.GEOMETRY).getJSONArray(GeoJsonWriter.COORDINATES);
						
						Point centroid = factory.createPoint(new Coordinate(coords.getDouble(0), coords.getDouble(1)));
						if(!poly.contains(centroid)) {
							iterator.remove();
							continue;
						}
						
						matched.put("action", "remove");
						String poiId = poi.getString("id");
						matched.put("actionDetailed", 
								"Remove merged with polygonal boundary poi point." + poiId);
						
						JSONObject matchedProperties = matched.getJSONObject(GeoJsonWriter.PROPERTIES);
						for(String key : (Set<String>)matchedProperties.keySet()) {
							if(!poiProperties.has(key)) {
								poiProperties.put(key, matchedProperties.get(key));
							}
						}
					}
					
					//move center to the poi point instead of centroid
					if(dubles.size() == 1) {
						JSONArray coords = dubles.get(0).getJSONObject(GeoJsonWriter.GEOMETRY).getJSONArray(GeoJsonWriter.COORDINATES);
						poi.getJSONObject(GeoJsonWriter.GEOMETRY).put(GeoJsonWriter.COORDINATES, coords);
					}
					
				}
			}
		}
	}

	private void filterMatchedPois(JSONObject poi,
			List<JSONObject> dubles) {
		
		String clazz = poi.getJSONArray("poiTypes").getString(0);
		
		Iterator<JSONObject> iterator = dubles.iterator();
		while (iterator.hasNext()) {
			
			JSONObject candidate = iterator.next();
			if(!clazz.equals(candidate.getJSONArray("poiTypes"))) {
				iterator.remove();
			}
		}
	}

	private void initializeMaps() {
		addr2streets = new HashMap<JSONObject, List<JSONObject>>(addrPoints.size()); 
		addr2bndries = new HashMap<JSONObject, List<JSONObject>>(addrPoints.size()); 
		street2bndries = new HashMap<JSONObject, List<List<JSONObject>>> (streets.size()); 
		place2bndries = new HashMap<JSONObject, List<JSONObject>>(places.size()); 
		poi2bndries = new HashMap<JSONObject, List<JSONObject>>(pois.size()); 
		addr2PlaceVoronoy = new HashMap<>(addrPoints.size()); 
		addr2NeighbourVoronoy = new HashMap<>(addrPoints.size()); 
		
		street2Junctions = new HashMap<Long, Set<JSONObject>>(junctions.size() * 2);
		
		poiPnt2Builng = new HashMap<>(poi2bdng.size());
		addrPnt2Builng = new HashMap<>(addr2bdng.size());

		addrPnt2AsStreet = new HashMap<>(associatedStreets.size() * 10);
	}

	private void readFeatures() {
		FileUtils.handleLines(src, new LineHandler() {
			
			@Override
			public void handle(String line) {
				String ftype = GeoJsonWriter.getFtype(line);
				
				switch(ftype) {
				
				//Not an error, two cases with same behaviour. 
				case FeatureTypes.ADMIN_BOUNDARY_FTYPE:
				case FeatureTypes.PLACE_BOUNDARY_FTYPE:
					boundaries.add(new JSONFeature(line));
					break;

				case FeatureTypes.ADDR_POINT_FTYPE:
					addrPoints.add(new JSONFeature(line));
					break;
					
				case FeatureTypes.NEIGHBOUR_DELONEY_FTYPE: 
					neighboursVoronoi.add(new JSONFeature(line));
					break;
					
				case FeatureTypes.PLACE_DELONEY_FTYPE:
					placesVoronoi.add(new JSONFeature(line));
					break;
					
				case FeatureTypes.HIGHWAY_FEATURE_TYPE:
					streets.add(new JSONFeature(line));
					break;
					
				case FeatureTypes.POI_FTYPE:
					pois.add(new JSONFeature(line));
					break;
					
				case FeatureTypes.JUNCTION_FTYPE:
					junctions.add(new JSONFeature(line));
					break;
					
				case FeatureTypes.POI_2_BUILDING:
					poi2bdng.add(new JSONFeature(line));
					break;
					
				case FeatureTypes.ADDR_NODE_2_BUILDING:
					addr2bdng.add(new JSONFeature(line));
					break;

				case FeatureTypes.PLACE_POINT_FTYPE:
					places.add(new JSONFeature(line));
					break;

				case FeatureTypes.ASSOCIATED_STREET:
					associatedStreets.add(new JSONFeature(line));
					break;
				}
			}
			
		});
	}
	
	private void join() {
		
		Collections.sort(boundaries, new Comparator<JSONObject>() {

			@Override
			public int compare(JSONObject b1, JSONObject b2) {
				int lvl1 = getBlevel(b1);

				int lvl2 = getBlevel(b2);
				
				return Integer.compare(lvl1, lvl2);
			}
			
		});
		
		for(JSONObject boundary : boundaries) {
			Polygon polygon = GeoJsonWriter.getPolygonGeometry(boundary);
			
			many2ManyJoin(boundary, polygon, addr2bndries, addrPointsIndex);
			many2ManyJoin(boundary, polygon, place2bndries, placesPointsIndex);
			many2ManyJoin(boundary, polygon, poi2bndries, poisIndex);
			highwaysJoin(boundary, polygon, street2bndries, streetsPointsIndex);
			
		}
		
		joinStreets2Addresses();
		
		one2OneJoin(placesVoronoi, addr2PlaceVoronoy);
		one2OneJoin(neighboursVoronoi, addr2NeighbourVoronoy);
		
		joinJunctionsWithStreets();
		
		joinBndg2Poi();
		joinBndg2Addr();

		fillAddr2AsStreet();
		
		//use clear because we will populate list with a same number of lines
		addrPoints.clear();
		
		for(Entry<JSONObject, List<JSONObject>> entry : addr2bndries.entrySet()) {
			List<JSONObject> boundaries = entry.getValue();
			if(necesaryBoundaries.isEmpty() || checkNecesaryBoundaries(boundaries)) {
				boundaries.addAll(common);
				
				String assStreetAddrKey = StringUtils.split(entry.getKey().getString("id"), '-')[2];
				
				addrPoints.add(handler.handle(
						entry.getKey(), 
						boundaries, 
						addr2streets.get(entry.getKey()),
						addr2PlaceVoronoy.get(entry.getKey()), 
						addr2NeighbourVoronoy.get(entry.getKey()), 
						addrPnt2AsStreet.get(assStreetAddrKey)));
			}
			
		}
		
		joinPoi2Addresses();
	}

	private boolean checkNecesaryBoundaries(List<JSONObject> joinedBoundaries) {
		for(JSONObject b : joinedBoundaries) {
			String osmId = StringUtils.split(b.getString("id"), '-')[2];
			if(necesaryBoundaries.contains(osmId)) {
				return true;
			}
		}
		return false;
	}

	private void fillAddr2AsStreet() {
		for(JSONObject association : associatedStreets) {
			JSONArray barray = association.getJSONArray("buildings");
			
			for(int i = 0; i < barray.length(); i++) {
				String key = barray.getString(i);
				addrPnt2AsStreet.put(key, association);
			}
		}
	}

	private void joinStreets2Addresses() {
		for (JSONObject strtJSON : streets) {
			LineString ls = GeoJsonWriter.getLineStringGeometry(
					strtJSON.getJSONObject("geometry").getJSONArray("coordinates"));
			
			@SuppressWarnings("deprecation")
			Geometry buffer = ls.buffer(STREET_BUFFER_DISTANCE, 2, BufferOp.CAP_ROUND);
			if(buffer instanceof Polygon) {
				many2ManyJoin(strtJSON, (Polygon) buffer, addr2streets, addrPointsIndex);
			}
			else if(buffer instanceof MultiPolygon) {
				for(int i = 0; i < buffer.getNumGeometries(); i++) {
					Polygon p = (Polygon) buffer.getGeometryN(i);
					if(p.isValid()) {
						many2ManyJoin(strtJSON, p, addr2streets, addrPointsIndex);
					}
				}
			}
		}
	}

	private void joinPoi2Addresses() {
		for(JSONObject poi : pois) {
			JSONArray coords = poi.getJSONObject(GeoJsonWriter.GEOMETRY).getJSONArray(GeoJsonWriter.COORDINATES);
			double lon = coords.getDouble(0);
			double lat = coords.getDouble(1);
			
			Coordinate p1 = new Coordinate(lon - POI_BUFFER_DISTANCE, lat - POI_BUFFER_DISTANCE);
			Coordinate p2 = new Coordinate(lon + POI_BUFFER_DISTANCE / 4, lat + POI_BUFFER_DISTANCE);
			
			@SuppressWarnings("unchecked")
			List<JSONObject> addrPnts = addrPointsIndex.query(new Envelope(p1, p2));
			
			if(addrPnts != null && !addrPnts.isEmpty()) {
				BestFitAddresses join = poiAddrJoinBuilder.join(poi, addrPnts);
				poi.put("joinedAddresses", join.asJSON());
			}
		}
	}

	private void joinBndg2Addr() {
		for(JSONObject obj : addr2bdng) {
			addrPnt2Builng.put(obj.getLong("nodeId"), obj);
		}

		for(JSONObject addr : addrPoints) {
			JSONObject meta = addr.getJSONObject(GeoJsonWriter.META);
			if ("node".equals(meta.getString("type"))) {
				JSONObject bndbg = poiPnt2Builng.get(meta.getLong("id"));
				putLinkedBuilding(addr, bndbg);
			}
		}
	}

	private void putLinkedBuilding(JSONObject addr, JSONObject bndbg) {
		if(bndbg != null) {
			JSONObject jb = new JSONObject();
			jb.put("id", bndbg.getJSONObject(GeoJsonWriter.META).getLong("id"));
			jb.put(GeoJsonWriter.PROPERTIES, bndbg.getJSONObject(GeoJsonWriter.PROPERTIES));
			addr.put("bndgWay", jb);
		}
	}

	private void joinBndg2Poi() {
		for(JSONObject obj : poi2bdng) {
			poiPnt2Builng.put(obj.getLong("nodeId"), obj);
		}

		for(JSONObject poi : pois) {
			JSONObject meta = poi.getJSONObject(GeoJsonWriter.META);
			if ("node".equals(meta.getString("type"))) {
				JSONObject bndbg = poiPnt2Builng.get(meta.getLong("id"));
				putLinkedBuilding(poi, bndbg);
			}
		}
	}

	private void write() {

		for(Entry<JSONObject, List<JSONObject>> entry : poi2bndries.entrySet()) {
			List<JSONObject> boundaries = entry.getValue();
			if(necesaryBoundaries.isEmpty() || checkNecesaryBoundaries(boundaries)) {
				boundaries.addAll(common);
				
				entry.getKey().put("boundaries", addressesParser.boundariesAsArray(entry.getKey(), boundaries));
			}
		}
		
		PrintWriter printWriter = null;
		try {
			printWriter = new PrintWriter(new FileOutputStream(src, true));
			
			for(JSONObject json : addrPoints) {
				GeoJsonWriter.addTimestamp(json);
				String geoJSONString = new JSONFeature(json).toString();
				
				assert GeoJsonWriter.getId(geoJSONString).equals(json.optString("id")) 
					: "Failed getId for " + geoJSONString;

				assert GeoJsonWriter.getFtype(geoJSONString).equals(FeatureTypes.ADDR_POINT_FTYPE) 
					: "Failed getFtype for " + geoJSONString;
				
				printWriter.println(geoJSONString);
			}
			
			for(Entry<JSONObject, List<List<JSONObject>>> entry : street2bndries.entrySet()) {
				
				List<List<JSONObject>> boundaries = new ArrayList<>(entry.getValue());
				
				JSONArray bndriesJSON = new JSONArray();
				for(List<JSONObject> b : boundaries) {
					if(necesaryBoundaries.isEmpty() || checkNecesaryBoundaries(b)) {
						b.addAll(common);
						bndriesJSON.put(addressesParser.boundariesAsArray(entry.getKey(), b));
					}
				}
				
				if(bndriesJSON.length() > 0) {
					JSONObject street = entry.getKey();
					street.put("boundaries", bndriesJSON);
					GeoJsonWriter.addTimestamp(street);
					
					String geoJSONString = new JSONFeature(street).toString();
					assert GeoJsonWriter.getId(geoJSONString).equals(street.optString("id")) 
					: "Failed getId for " + geoJSONString;
					
					assert GeoJsonWriter.getFtype(geoJSONString).equals(FeatureTypes.HIGHWAY_FEATURE_TYPE) 
					: "Failed getFtype for " + geoJSONString;
					
					printWriter.println(geoJSONString);
				}
					
			}
			
			for(JSONObject jun : junctions) {
				GeoJsonWriter.addTimestamp(jun);
				printWriter.println(jun.toString());
			}
			
			for(Entry<JSONObject, List<JSONObject>> entry : place2bndries.entrySet()) {
				List<JSONObject> boundaries = new ArrayList<>(entry.getValue());
				if(necesaryBoundaries.isEmpty() || checkNecesaryBoundaries(boundaries)) {
					boundaries.addAll(common);
					JSONObject place = entry.getKey();
					
					String name = place.getJSONObject(GeoJsonWriter.PROPERTIES).optString("name");
					for(JSONObject b : boundaries) {
						if(namesMatcher.isPlaceNameMatch(name, AddressesUtils.filterNameTags(b))) {
							place.put("matchedBoundary", b);
							break;
						}
					}
	
					place.put("boundaries", addressesParser.boundariesAsArray(entry.getKey(), boundaries));
					GeoJsonWriter.addTimestamp(place);
					
					String geoJSONString = new JSONFeature(place).toString();
					printWriter.println(geoJSONString);
				}
			}
			
			for(JSONObject poi : pois) {
				GeoJsonWriter.addTimestamp(poi);
				printWriter.println(poi.toString());
			}

			printWriter.flush();
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to write joined stripe. File: " + this.src, e);
		}
		finally {
			if(printWriter != null) {
				printWriter.close();
			}
		}
	}
	
	private void joinJunctionsWithStreets() {
		for(JSONObject junction : junctions) {
			JSONArray hwIds = junction.optJSONArray("ways");
			
			for(int i = 0; i < hwIds.length(); i++) {
				Long hw = hwIds.getLong(i);
				if(street2Junctions.get(hw) == null) {
					street2Junctions.put(hw, new HashSet<JSONObject>());
				}
				street2Junctions.get(hw).add(junction);
			}
		}
		
		for(JSONObject way : streets) {
			Set<JSONObject> junctionsSet = street2Junctions.get(way.getJSONObject(GeoJsonWriter.META).getLong("id"));
			if(junctionsSet != null) {
				for(JSONObject j : junctionsSet) {
					JSONArray waysRefers = j.optJSONArray("waysRefers");
					if(waysRefers == null) {
						waysRefers = new JSONArray();
						j.put("waysRefers", waysRefers);
					}
					
					waysRefers.put(JSONFeature.asRefer(way));
				}
			}
		}
	}

	private void many2ManyJoin(JSONObject object, Polygon polyg, Map<JSONObject, List<JSONObject>> result, Quadtree index) {
		Envelope polygonEnvelop = polyg.getEnvelopeInternal();
		for (Object entry : index.query(polygonEnvelop)) {
			
			JSONArray pntg = ((JSONObject)entry).getJSONObject(GeoJsonWriter.GEOMETRY).getJSONArray(GeoJsonWriter.COORDINATES);
			Coordinate pnt = new Coordinate(pntg.getDouble(0), pntg.getDouble(1));;
			JSONObject obj = (JSONObject) entry;
			
			if(polyg.intersects(factory.createPoint(pnt))) {
				if(result.get(obj) == null) {
					result.put(obj, new ArrayList<JSONObject>());
				}
				
				result.get(obj).add(object);
			}
		}
	}

	private void highwaysJoin(JSONObject newBoundary, Polygon polyg, Map<JSONObject, List<List<JSONObject>>> result, Quadtree index) {
		Envelope polygonEnvelop = polyg.getEnvelopeInternal();
		for (Object entry : index.query(polygonEnvelop)) {
			
			Coordinate pnt = (Coordinate) ((Object[])entry)[0];
			JSONObject highway = (JSONObject) ((Object[])entry)[1];
			
			int bLevel = getBlevel(newBoundary);
			
			if(polyg.intersects(factory.createPoint(pnt))) {
				if(result.get(highway) == null) {
					result.put(highway, new ArrayList<List<JSONObject>>());
				}
				
				List<List<JSONObject>> listList = result.get(highway);
				
				//start new boundaries row
				if(listList.size() == 0) {
					ArrayList<JSONObject> boundariesRow = new ArrayList<JSONObject>();
					boundariesRow.add(newBoundary);
					listList.add(boundariesRow);
				}
				
				//1 row (as usual)
				else if(listList.size() == 1) {
					List<JSONObject> row = listList.get(0);
					JSONObject last = row.get(row.size() - 1);
					int oldBLevel = getBlevel(last);
					
					//we have two or more boundaries with same level
					//so we need to split addr row
					if(oldBLevel > 0 && oldBLevel == bLevel) {
						List<JSONObject> newRow = new ArrayList<>(row);
						newRow.remove(newRow.size() - 1);
						newRow.add(newBoundary);
						listList.add(newRow);
					}
					else {
						row.add(newBoundary);
					}
				}
				
				//already splitted up
				else {
					//find our
					for (List<JSONObject> row : listList) {
						JSONObject last = row.get(row.size() - 1);
						int oldBLevel = getBlevel(last);
						
						//add to new row
						if(oldBLevel > 0 && oldBLevel == bLevel) {
							List<JSONObject> newRow = new ArrayList<>(row);
							newRow.remove(newRow.size() - 1);
							newRow.add(newBoundary);
							listList.add(newRow);
							break;
						}
						
						//add to exists row (with coordinates check)
						else {
							JSONArray coords = last.getJSONObject(GeoJsonWriter.GEOMETRY)
									.getJSONArray(GeoJsonWriter.COORDINATES);
							
							Point centroid = GeoJsonWriter.getPolygonGeometry(coords).getCentroid();
							
							if(polyg.contains(centroid)) {
								row.add(newBoundary);
							}
						}
					}
				}
			}
		}
	}

	private int getBlevel(JSONObject newBoundary) {
		int bLevel = 0;
		String addrLevel = addressesParser.getAddrLevel(newBoundary);
		if(addrLevel != null) {
			bLevel = addrLevelComparator.getLVLSize(addrLevel);
		}
		return bLevel;
	}

	@SuppressWarnings("unchecked")
	private void one2OneJoin(List<JSONObject> polygons, Map<JSONObject, JSONObject> result) {
		for(JSONObject placeV : polygons) {
			Polygon polyg = GeoJsonWriter.getPolygonGeometry(placeV);
			Envelope polygonEnvelop = polyg.getEnvelopeInternal();
			for (JSONObject pnt : (List<JSONObject>)addrPointsIndex.query(polygonEnvelop)) {
				JSONArray pntg = pnt.getJSONObject(GeoJsonWriter.GEOMETRY).getJSONArray(GeoJsonWriter.COORDINATES);
				if(polyg.contains(factory.createPoint(new Coordinate(pntg.getDouble(0), pntg.getDouble(1))))){
					result.put(pnt, placeV);
				}
			}
		}
	}

}
