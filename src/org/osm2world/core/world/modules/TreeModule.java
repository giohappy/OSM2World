package org.osm2world.core.world.modules;

import static org.osm2world.core.world.modules.common.WorldModuleGeometryUtil.filterWorldObjectCollisions;
import static org.osm2world.core.world.modules.common.WorldModuleParseUtil.parseHeight;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

import org.apache.commons.configuration.Configuration;
import org.openstreetmap.josm.plugins.graphview.core.data.Tag;
import org.osm2world.core.map_data.data.MapArea;
import org.osm2world.core.map_data.data.MapData;
import org.osm2world.core.map_data.data.MapElement;
import org.osm2world.core.map_data.data.MapNode;
import org.osm2world.core.map_data.data.MapWaySegment;
import org.osm2world.core.map_data.data.overlaps.MapOverlap;
import org.osm2world.core.map_elevation.data.GroundState;
import org.osm2world.core.math.AxisAlignedBoundingBoxXZ;
import org.osm2world.core.math.GeometryUtil;
import org.osm2world.core.math.VectorXYZ;
import org.osm2world.core.math.VectorXZ;
import org.osm2world.core.math.datastructures.IntersectionTestObject;
import org.osm2world.core.target.RenderableToAllTargets;
import org.osm2world.core.target.Target;
import org.osm2world.core.target.common.material.Material;
import org.osm2world.core.target.common.material.Materials;
import org.osm2world.core.target.povray.POVRayTarget;
import org.osm2world.core.target.povray.RenderableToPOVRay;
import org.osm2world.core.world.data.AreaWorldObject;
import org.osm2world.core.world.data.NodeWorldObject;
import org.osm2world.core.world.data.WaySegmentWorldObject;
import org.osm2world.core.world.data.WorldObject;
import org.osm2world.core.world.modules.common.ConfigurableWorldModule;
import org.osm2world.core.world.modules.common.WorldModuleBillboardUtil;

/**
 * adds trees, tree rows, tree groups and forests to the world
 */
public class TreeModule extends ConfigurableWorldModule {
	
	private boolean useBillboards = false;
	
	@Override
	public void setConfiguration(Configuration config) {
		super.setConfiguration(config);
		useBillboards = config.getBoolean("useBillboards", false);
	}
	
	@Override
	public final void applyTo(MapData mapData) {
		
		for (MapNode node : mapData.getMapNodes()) {
			
			if (node.getTags().contains("natural", "tree")) {
				node.addRepresentation(new Tree(node));
			}
			
		}

		for (MapWaySegment segment : mapData.getMapWaySegments()) {
			
			if (segment.getTags().contains(new Tag("natural", "tree_row"))) {
				segment.addRepresentation(new TreeRow(segment));
			}
			
		}

		for (MapArea area : mapData.getMapAreas()) {
			
			if (area.getTags().contains("natural", "wood")
					|| area.getTags().contains("landuse", "forest")
					|| area.getTags().containsKey("wood")
					|| area.getTags().contains("landuse", "orchard")) {
				area.addRepresentation(new Forest(area, mapData));
			}
			
		}
		
	}

	private static final float DEFAULT_TREE_HEIGHT = 10;
	private static final float TREE_RADIUS_PER_HEIGHT = 0.2f;
	
	private void renderTree(Target<?> target,
			MapElement element,	VectorXZ pos) {
		
		VectorXYZ posXYZ = element.getElevationProfile().getWithEle(pos);
		boolean fruit = isFruitTree(element, pos);
		boolean coniferous = isConiferousTree(element, pos);
		double height = getTreeHeight(element);
		
		if (useBillboards) {
			
			//"random" decision based on x coord
			boolean mirrored = (long)(pos.getX()) % 2 == 0;
			
			Material material = fruit
					? Materials.TREE_BILLBOARD_BROAD_LEAVED_FRUIT
					: coniferous
					? Materials.TREE_BILLBOARD_CONIFEROUS
					: Materials.TREE_BILLBOARD_BROAD_LEAVED;
			
			WorldModuleBillboardUtil.renderCrosstree(target, material, posXYZ,
					(fruit ? 1.0 : 0.5 ) * height, height, mirrored);
			
		} else {
			
			renderTreeGeometry(target, posXYZ, coniferous, height);
			
		}
		
	}
	
	private static void renderTreeGeometry(Target<?> target,
			VectorXYZ posXYZ, boolean coniferous, double height) {
		
		double stemRatio = coniferous?0.3:0.5;
		double radius = height*TREE_RADIUS_PER_HEIGHT;
		
		target.drawColumn(Materials.TREE_TRUNK,
				null, posXYZ, height*stemRatio,
				radius / 4, radius / 5, false, true);
		
		target.drawColumn(Materials.TREE_CROWN,
				null, posXYZ.y(posXYZ.y+height*stemRatio),
				height*(1-stemRatio),
				radius,
				coniferous ? 0 : radius,
				true, true);
	}
		
	private static boolean isConiferousTree(MapElement element, VectorXZ pos) {
		
		String typeValue = element.getTags().getValue("wood");
		
		if (typeValue == null) {
			typeValue = element.getTags().getValue("type");
		}
		
		if ("broad_leaved".equals(typeValue)
				|| "broad_leafed".equals(typeValue) // both values are common
				|| "deciduous".equals(typeValue)) {
			return false;
		} else if ("coniferous".equals(typeValue)) {
			return true;
		} else { //mixed or undefined
			//"random" decision based on x coord
			return (long)(pos.getX()) % 2 == 0;
		}
		
	}
	
	private static boolean isFruitTree(MapElement element, VectorXZ pos) {
		
		if (element.getTags().contains("landuse", "orchard")) {
			return true;
		}
		
		String species = element.getTags().getValue("species");
		
		return species != null &&
				species.contains("malus");
		
	}
	
	/**
	 * parse height (for forests, add some random factor)
	 */
	private static double getTreeHeight(MapElement element) {
		
		float heightFactor = 1;
		if (element instanceof MapArea) {
			heightFactor += -0.25f + 0.5f * Math.random();
		}
		
		return heightFactor *
				parseHeight(element.getTags(), DEFAULT_TREE_HEIGHT);
		
	}
	
	private POVRayTarget previousDeclarationTarget = null;
	
	private void addTreeDeclarationsTo(POVRayTarget target) {
		if (target != previousDeclarationTarget) {
		
			previousDeclarationTarget = target;
			
			target.append("#ifndef (broad_leaved_tree)\n");
			target.append("#declare broad_leaved_tree = object { union {\n");
			renderTreeGeometry(target, VectorXYZ.NULL_VECTOR, false, 1);
			target.append("} }\n#end\n\n");
			
			target.append("#ifndef (coniferous_tree)\n");
			target.append("#declare coniferous_tree = object { union {\n");
			renderTreeGeometry(target, VectorXYZ.NULL_VECTOR, true, 1);
			target.append("} }\n#end\n\n");
			
		}
	}

	private void renderTree(POVRayTarget target,
			MapElement element, VectorXZ pos) {
		
		double height = getTreeHeight(element);
		
		//rotate randomly for variation
		float yRotation = (float) Math.random() * 360;
		
		//add union of stem and leaves
		if (isConiferousTree(element, pos)) {
			target.append("object { coniferous_tree rotate ");
		} else {
			target.append("object { broad_leaved_tree rotate ");
		}
		
		target.append(Float.toString(yRotation));
		target.append("*y scale ");
		target.append(height);
		target.append(" translate ");
		target.appendVector(pos.x, 0, pos.z);
		target.append(" }\n");
		
	}
	
	private class Tree
		implements NodeWorldObject, IntersectionTestObject,
		RenderableToAllTargets, RenderableToPOVRay {
		
		private final MapNode node;

		public Tree(MapNode node) {
			this.node = node;
		}
		
		@Override
		public MapElement getPrimaryMapElement() {
			return node;
		}
		
		@Override
		public GroundState getGroundState() {
			return GroundState.ON;
		}
		
		@Override
		public double getClearingAbove(VectorXZ pos) {
			return getTreeHeight(node);
		}
		
		@Override
		public double getClearingBelow(VectorXZ pos) {
			return 0;
		}
		
		@Override
		public AxisAlignedBoundingBoxXZ getAxisAlignedBoundingBoxXZ() {
			return new AxisAlignedBoundingBoxXZ(Collections.singleton(node.getPos()));
		}
		
		@Override
		public void renderTo(Target<?> target) {
			renderTree(target, node, node.getPos());
		}
		
		@Override
		public void addDeclarationsTo(POVRayTarget target) {
			addTreeDeclarationsTo(target);
		}
		
		@Override
		public void renderTo(POVRayTarget target) {
			renderTree(target, node, node.getPos());
		}
		
	}

	private class TreeRow implements WaySegmentWorldObject, RenderableToAllTargets, RenderableToPOVRay {

		private final Collection<VectorXZ> treePositions;
		private final MapWaySegment line;
		
		public TreeRow(MapWaySegment line) {
			
			this.line = line;
			
			//TODO: spread along a full way
			
			treePositions = GeometryUtil.equallyDistributePointsAlong(
					4 /* TODO: derive from tree count */ ,
					false /* TODO: should be true once a full way is covered */,
					line.getStartNode().getPos(), line.getEndNode().getPos());
			
		}
		
		@Override
		public MapElement getPrimaryMapElement() {
			return line;
		}
		
		@Override
		public VectorXZ getEndPosition() {
			return line.getEndNode().getPos();
		}

		@Override
		public VectorXZ getStartPosition() {
			return line.getStartNode().getPos();
		}

		@Override
		public double getClearingAbove(VectorXZ pos) {
			return 5;
		}

		@Override
		public double getClearingBelow(VectorXZ pos) {
			return 0;
		}

		@Override
		public GroundState getGroundState() {
			return GroundState.ON;
		}

		@Override
		public void renderTo(Target<?> target) {
			for (VectorXZ pos : treePositions) {
				renderTree(target, line, pos);
			}
		}

		@Override
		public void addDeclarationsTo(POVRayTarget target) {
			addTreeDeclarationsTo(target);
		}
		
		@Override
		public void renderTo(POVRayTarget target) {
			for (VectorXZ pos : treePositions) {
				renderTree(target, line, pos);
			}
		}
		
	}
	

	private class Forest implements AreaWorldObject,
		RenderableToPOVRay, RenderableToAllTargets {

		private final MapArea area;
		private final MapData mapData;
		
		private Collection<VectorXZ> treePositions = null;
		
		public Forest(MapArea area, MapData mapData) {
			this.area = area;
			this.mapData = mapData;
		}

		private void createTreePositions(double density) {
			
			/* collect other objects that the trees should not be placed on */
			
			Collection<WorldObject> avoidedObjects = new ArrayList<WorldObject>();
			
			for (MapOverlap<?, ?> overlap : area.getOverlaps()) {
				for (WorldObject otherRep : overlap.getOther(area).getRepresentations()) {
					
					if (otherRep.getGroundState() == GroundState.ON) {
						avoidedObjects.add(otherRep);
					}
				
				}
			}
			
			/* place the trees */
			
			treePositions =
				GeometryUtil.distributePointsOn(area.getOsmObject().id,
						area.getPolygon(), mapData.getBoundary(),
						density, 0.3f);
			
			filterWorldObjectCollisions(treePositions, avoidedObjects);
									
		}
		
		@Override
		public MapElement getPrimaryMapElement() {
			return area;
		}

		@Override
		public double getClearingAbove(VectorXZ pos) {
			return 2;
		}

		@Override
		public double getClearingBelow(VectorXZ pos) {
			return 0;
		}

		@Override
		public GroundState getGroundState() {
			return GroundState.ON;
		}
		
		@Override
		public void renderTo(POVRayTarget target) {
			if (treePositions == null) {
				createTreePositions(config.getDouble("treesPerSquareMeter", 0.01f));
			}
			for (VectorXZ pos : treePositions) {
				renderTree(target, area, pos);
			}
		}
		
		@Override
		public void addDeclarationsTo(POVRayTarget target) {
			addTreeDeclarationsTo(target);
		}
		
		@Override
		public void renderTo(Target<?> target) {
			if (treePositions == null) {
				createTreePositions(config.getDouble("treesPerSquareMeter", 0.001f));
					//lower default density than POVRay for performance reasons
			}
			for (VectorXZ pos : treePositions) {
				renderTree(target, area, pos);
			}
		}
		
	}
		
}
