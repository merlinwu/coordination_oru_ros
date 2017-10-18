package se.oru.coordination.coordinator.util;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.metacsp.multi.spatial.DE9IM.GeometricShapeDomain;
import org.metacsp.multi.spatioTemporal.paths.Pose;
import org.metacsp.multi.spatioTemporal.paths.TrajectoryEnvelope;
import org.ros.RosCore;
import org.ros.concurrent.CancellableLoop;
import org.ros.message.Duration;
import org.ros.namespace.GraphName;
import org.ros.node.ConnectedNode;
import org.ros.node.DefaultNodeMainExecutor;
import org.ros.node.Node;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;
import org.ros.node.topic.Publisher;

import com.vividsolutions.jts.geom.Coordinate;

import nav_msgs.OccupancyGrid;
import se.oru.coordination.coordination_oru.RobotReport;
import se.oru.coordination.coordination_oru.TrajectoryEnvelopeCoordinator;
import se.oru.coordination.coordination_oru.util.FleetVisualization;
import se.oru.coordination.coordination_oru.util.Missions;
import visualization_msgs.MarkerArray;

public class RVizVisualization implements FleetVisualization, NodeMain {

	private ConnectedNode node = null;
	//private TrajectoryEnvelopeCoordinator tec = null;
	private HashMap<Integer,Publisher<visualization_msgs.MarkerArray>> robotStatusPublishers = null;
	private HashMap<Integer,Publisher<visualization_msgs.MarkerArray>> dependencyPublishers = null;
	private HashMap<Integer,ArrayList<visualization_msgs.Marker>> robotStatusMarkers = null;
	private HashMap<Integer,ArrayList<visualization_msgs.Marker>> dependencyMarkers = null;
	private HashMap<Integer,visualization_msgs.Marker> envelopeMarkers = null;
	private boolean ready = false;
	private String mapFileName = null;
	
	public RVizVisualization(TrajectoryEnvelopeCoordinator tec) throws URISyntaxException, UnknownHostException {
		//this.tec = tec;
		this.robotStatusPublishers = new HashMap<Integer,Publisher<visualization_msgs.MarkerArray>>();
		this.dependencyPublishers = new HashMap<Integer,Publisher<visualization_msgs.MarkerArray>>();
		this.robotStatusMarkers = new HashMap<Integer,ArrayList<visualization_msgs.Marker>>();
		this.dependencyMarkers = new HashMap<Integer,ArrayList<visualization_msgs.Marker>>();

		NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic("localhost");
		NodeMainExecutor executor = DefaultNodeMainExecutor.newDefault();
		executor.execute(this, nodeConfiguration);

		RosCore mRosCore = RosCore.newPublic("localhost", 11311);
		mRosCore.start();
		try {
			mRosCore.awaitStart(5, TimeUnit.SECONDS);
		}
		catch (InterruptedException e) { e.printStackTrace(); }
		System.out.println("ROS-core started");
	}

	private BufferedImage toGrayScale(BufferedImage imgIn) {
		// Create a new buffer to BYTE_GRAY
		BufferedImage img = new BufferedImage(imgIn.getWidth(), imgIn.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
		WritableRaster raster = img.getRaster();
		WritableRaster rasterJPEG = imgIn.getRaster();
		// Foreach pixel we transform it to Gray Scale and put it on the same image
		for(int h=0; h < img.getHeight(); h++)
			for(int w=0; w < img.getWidth(); w++) {
				int[] p = new int[4];
				rasterJPEG.getPixel(w, h, p);
				p[0] = (int) (0.3 * p[0]);
				p[1] = (int) (0.59 * p[1]);
				p[2] = (int) (0.11 * p[2]);
				int y = p[0] + p[1] + p[2];
				raster.setSample(w,h,0,y);
			}
		return img;
	}

	public void setMapFileName(String mapYAMLFile, String prefix) {
		this.mapFileName = Missions.getProperty("image", mapYAMLFile);
		if (prefix != null) this.mapFileName = prefix + File.separator + this.mapFileName;
		while (!ready) {
			try { Thread.sleep(100); }
			catch (InterruptedException e) { e.printStackTrace(); }
		}
		if (mapFileName != null) {
			try {
				final OccupancyGrid occMap = node.getTopicMessageFactory().newFromType(OccupancyGrid._TYPE);
				BufferedImage imgIn = ImageIO.read(new File(mapFileName));
				BufferedImage img = toGrayScale(imgIn);
				System.out.println("Loaded map: " + img.getHeight() + "x" + img.getWidth());
				WritableRaster raster = img.getRaster();
				DataBufferByte data = (DataBufferByte)raster.getDataBuffer();
				ChannelBuffer buffer = ChannelBuffers.copiedBuffer(ByteOrder.nativeOrder(), data.getData());
				occMap.setData(buffer);
				occMap.getHeader().setFrameId("/map");
				occMap.getInfo().setHeight((int)(img.getHeight()));
				occMap.getInfo().setWidth((int)(img.getWidth()));
				geometry_msgs.Pose pose = node.getTopicMessageFactory().newFromType(geometry_msgs.Pose._TYPE);
				pose.getPosition().setX(0);
				pose.getPosition().setY(0);
				occMap.getInfo().setOrigin(pose);
				double res = Double.parseDouble(Missions.getProperty("resolution", mapYAMLFile));
				occMap.getInfo().setResolution((float)res);
				final Publisher<OccupancyGrid> publisher = node.newPublisher("/map", OccupancyGrid._TYPE);
				node.executeCancellableLoop(new CancellableLoop() {
					@Override
					protected void loop() throws InterruptedException {
						publisher.publish(occMap);
						Thread.sleep(1000);
					}
				});

			}
			catch (IOException e) { e.printStackTrace(); }	
		}

	}


	@Override
	public void displayRobotState(TrajectoryEnvelope te, RobotReport rr) {
		if (ready) {
			double x = rr.getPose().getX();
			double y = rr.getPose().getY();
			double theta = rr.getPose().getTheta();

			visualization_msgs.Marker marker = node.getTopicMessageFactory().newFromType(visualization_msgs.Marker._TYPE);
			marker.getHeader().setFrameId("/map");
			marker.getScale().setX(0.1f);
			marker.getScale().setY(0.1f);
			marker.getScale().setZ(0.1f);
			marker.getColor().setR(100f);
			marker.getColor().setG(0.0f);
			marker.getColor().setB(0.0f);
			marker.getColor().setA(0.8f);
			marker.setAction(visualization_msgs.Marker.ADD);                                
			marker.setNs("current_pose");
			marker.setType(visualization_msgs.Marker.LINE_STRIP);
			marker.setId(te.getRobotID());
			marker.setLifetime(new Duration(10.0));

			ArrayList<geometry_msgs.Point> points = new ArrayList<geometry_msgs.Point>();
			Coordinate[] coords = TrajectoryEnvelope.getFootprint(te.getFootprint(), x, y, theta).getCoordinates();
			for (Coordinate coord : coords) {
				geometry_msgs.Point point = node.getTopicMessageFactory().newFromType(geometry_msgs.Point._TYPE);
				point.setX(coord.x);
				point.setY(coord.y);
				point.setZ(0.0);
				points.add(point);
			}
			points.add(points.get(0));
			marker.setPoints(points);
			if (!this.robotStatusPublishers.containsKey(rr.getRobotID())) {
				Publisher<visualization_msgs.MarkerArray> markerArrayPublisher = node.newPublisher("robot"+rr.getRobotID()+"/status", visualization_msgs.MarkerArray._TYPE);
				this.robotStatusPublishers.put(rr.getRobotID(), markerArrayPublisher);
				synchronized(robotStatusMarkers) {
					this.robotStatusMarkers.put(rr.getRobotID(), new ArrayList<visualization_msgs.Marker>());
				}
			}
			synchronized(robotStatusMarkers) {
				this.robotStatusMarkers.get(rr.getRobotID()).add(marker);
			}

			//////////////
			visualization_msgs.Marker markerName = node.getTopicMessageFactory().newFromType(visualization_msgs.Marker._TYPE);
			markerName.getHeader().setFrameId("/map");
			markerName.getScale().setX(1.0f);
			markerName.getScale().setY(1.0f);
			markerName.getScale().setZ(1.0f);
			markerName.getColor().setR(1.0f);
			markerName.getColor().setG(1.0f);
			markerName.getColor().setB(1.0f);
			markerName.getColor().setA(0.8f);
			markerName.setAction(visualization_msgs.Marker.ADD);                                
			markerName.setNs("robot_state");
			markerName.setType(visualization_msgs.Marker.TEXT_VIEW_FACING);
			//markerName.setId(te.getRobotID());
			markerName.setLifetime(new Duration(10.0));
			markerName.setText("R" + te.getRobotID() + ": " + rr.getPathIndex());
			geometry_msgs.Pose pose = node.getTopicMessageFactory().newFromType(geometry_msgs.Pose._TYPE);
			geometry_msgs.Point pos = node.getTopicMessageFactory().newFromType(geometry_msgs.Point._TYPE);
			pos.setX(x);
			pos.setY(y);
			pos.setZ(0);
			pose.setPosition(pos);
			markerName.setPose(pose);
			synchronized(robotStatusMarkers) {
				this.robotStatusMarkers.get(rr.getRobotID()).add(markerName);
			}
		}
	}

	@Override
	public void displayDependency(RobotReport rrWaiting, RobotReport rrDriving, String dependencyDescriptor) {
		if(ready) {
			Pose from = rrWaiting.getPose();
			Pose to = rrDriving.getPose();
			visualization_msgs.Marker mArrow = node.getTopicMessageFactory().newFromType(visualization_msgs.Marker._TYPE);
			mArrow.setAction(visualization_msgs.Marker.ADD);
			mArrow.setNs(dependencyDescriptor);
			mArrow.setType(visualization_msgs.Marker.ARROW);
			ArrayList<geometry_msgs.Point> points = new ArrayList<geometry_msgs.Point>();		
			geometry_msgs.Point pointFrom = node.getTopicMessageFactory().newFromType(geometry_msgs.Point._TYPE);
			geometry_msgs.Point pointTo = node.getTopicMessageFactory().newFromType(geometry_msgs.Point._TYPE);
			pointFrom.setX(from.getX());
			pointFrom.setY(from.getY());
			pointFrom.setZ(0.0);
			points.add(pointFrom);
			pointTo.setX(to.getX());
			pointTo.setY(to.getY());
			pointTo.setZ(0.0);
			points.add(pointTo);
			mArrow.setPoints(points);
			mArrow.setId(dependencyDescriptor.hashCode());
			mArrow.getHeader().setFrameId("/map");
			mArrow.getScale().setX(0.4);
			mArrow.getScale().setY(1.0);
			mArrow.getScale().setZ(1.2);
			mArrow.getColor().setR(15.0f);
			mArrow.getColor().setG(100.0f);
			mArrow.getColor().setB(200.0f);
			mArrow.getColor().setA(0.2f);
			mArrow.setLifetime(new Duration(1.0));
			if (!this.dependencyPublishers.containsKey(rrWaiting.getRobotID())) {
				Publisher<visualization_msgs.MarkerArray> markerArrayPublisher = node.newPublisher("robot"+rrWaiting.getRobotID()+"/deps", visualization_msgs.MarkerArray._TYPE);
				this.dependencyPublishers.put(rrWaiting.getRobotID(), markerArrayPublisher);
				synchronized(dependencyMarkers) {
					this.dependencyMarkers.put(rrWaiting.getRobotID(), new ArrayList<visualization_msgs.Marker>());
				}
			}
			synchronized(dependencyMarkers) {
				this.dependencyMarkers.get(rrWaiting.getRobotID()).add(mArrow);
			}
		}
	}

	@Override
	public void updateVisualization() {
		for (Entry<Integer, Publisher<MarkerArray>> entry : robotStatusPublishers.entrySet()) {
			synchronized(robotStatusMarkers) {
				if (!robotStatusMarkers.get(entry.getKey()).isEmpty()) { 
					visualization_msgs.MarkerArray ma = node.getTopicMessageFactory().newFromType(visualization_msgs.MarkerArray._TYPE);
					ArrayList<visualization_msgs.Marker> copy = new ArrayList<visualization_msgs.Marker>();
					for (visualization_msgs.Marker m : robotStatusMarkers.get(entry.getKey())) copy.add(m);
					if (envelopeMarkers.containsKey(entry.getKey())) copy.add(envelopeMarkers.get(entry.getKey()));
					ma.setMarkers(copy);
					entry.getValue().publish(ma);
					robotStatusMarkers.get(entry.getKey()).clear();
				}
			}
		}
		for (Entry<Integer, Publisher<MarkerArray>> entry : dependencyPublishers.entrySet()) {
			synchronized(dependencyMarkers) {
				if (!dependencyMarkers.get(entry.getKey()).isEmpty()) { 				
					visualization_msgs.MarkerArray ma = node.getTopicMessageFactory().newFromType(visualization_msgs.MarkerArray._TYPE);
					ArrayList<visualization_msgs.Marker> copy = new ArrayList<visualization_msgs.Marker>();
					for (visualization_msgs.Marker m : dependencyMarkers.get(entry.getKey())) copy.add(m);
					ma.setMarkers(copy);
					entry.getValue().publish(ma);
					dependencyMarkers.get(entry.getKey()).clear();
				}
			}
		}
	}


	@Override
	public void onError(Node arg0, Throwable arg1) {
		// TODO Auto-generated method stub

	}


	@Override
	public void onShutdown(Node arg0) {
		// TODO Auto-generated method stub

	}


	@Override
	public void onShutdownComplete(Node arg0) {
		// TODO Auto-generated method stub

	}


	@Override
	public void onStart(ConnectedNode arg0) {
		this.node = arg0;		
		while (true) {
			try {
				node.getCurrentTime();
				break;
			}
			catch(NullPointerException e) { }
		}
		this.ready = true;
	}


	@Override
	public GraphName getDefaultNodeName() {
		return GraphName.of("coordinator_viz");
	}

	@Override
	public void addEnvelope(TrajectoryEnvelope te) {
		GeometricShapeDomain dom = (GeometricShapeDomain)te.getEnvelopeVariable().getDomain();
		Coordinate[] verts = dom.getGeometry().getCoordinates();

		visualization_msgs.Marker marker = node.getTopicMessageFactory().newFromType(visualization_msgs.Marker._TYPE);
		marker.getHeader().setFrameId("/map");
		marker.getScale().setX(0.1f);
		marker.getScale().setY(0.1f);
		marker.getScale().setZ(0.1f);
		marker.getColor().setR(50f);
		marker.getColor().setG(50.0f);
		marker.getColor().setB(0.0f);
		marker.getColor().setA(0.8f);
		marker.setAction(visualization_msgs.Marker.ADD);
		marker.setNs("current_envelope");
		marker.setType(visualization_msgs.Marker.LINE_STRIP);
		marker.setId(te.getRobotID());
		marker.setLifetime(new Duration(1.0));

		ArrayList<geometry_msgs.Point> points = new ArrayList<geometry_msgs.Point>();
		for (Coordinate coord : verts) {
			geometry_msgs.Point point = node.getTopicMessageFactory().newFromType(geometry_msgs.Point._TYPE);
			point.setX(coord.x);
			point.setY(coord.y);
			point.setZ(0.0);
			points.add(point);
		}
		points.add(points.get(0));
		marker.setPoints(points);

		if (this.envelopeMarkers == null) this.envelopeMarkers = new HashMap<Integer,visualization_msgs.Marker>();
		synchronized(envelopeMarkers) {
			this.envelopeMarkers.put(te.getRobotID(),marker);
		}

	}

	@Override
	public void removeEnvelope(TrajectoryEnvelope te) {
		synchronized(envelopeMarkers) {
			this.envelopeMarkers.remove(te.getRobotID());
		}		
	}

}