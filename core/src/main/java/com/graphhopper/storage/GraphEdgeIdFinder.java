/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.storage;

import com.graphhopper.coll.GHBitSet;
import com.graphhopper.coll.GHIntHashSet;
import com.graphhopper.coll.GHTBitSet;
import com.graphhopper.routing.util.EdgeFilter;
import com.graphhopper.storage.index.LocationIndex;
import com.graphhopper.storage.index.QueryResult;
import com.graphhopper.util.BreadthFirstSearch;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PointList;
import com.graphhopper.util.shapes.Polygon;
import com.graphhopper.util.shapes.*;
import org.locationtech.jts.algorithm.RectangleLineIntersector;
import org.locationtech.jts.geom.*;

import java.util.ArrayList;
import java.util.List;

import static com.graphhopper.util.shapes.BBox.toEnvelope;

/**
 * This class allows to find edges (or construct shapes) from shape filter.
 *
 * @author Robin Boldt
 */
public class GraphEdgeIdFinder {

    private final Graph graph;
    private final LocationIndex locationIndex;

    public GraphEdgeIdFinder(Graph graph, LocationIndex locationIndex) {
        this.graph = graph;
        this.locationIndex = locationIndex;
    }

    /**
     * This method fills the edgeIds hash with edgeIds found close (exact match) to the specified point
     */
    public void findClosestEdgeToPoint(GHIntHashSet edgeIds, GHPoint point, EdgeFilter filter) {
        findClosestEdge(edgeIds, point.getLat(), point.getLon(), filter);
    }

    /**
     * This method fills the edgeIds hash with edgeIds found close (exact match) to the specified lat,lon
     */
    public void findClosestEdge(GHIntHashSet edgeIds, double lat, double lon, EdgeFilter filter) {
        QueryResult qr = locationIndex.findClosest(lat, lon, filter);
        if (qr.isValid())
            edgeIds.add(qr.getClosestEdge().getEdge());
    }

    /**
     * This method fills the edgeIds hash with edgeIds found inside the specified shape
     */
    public void findEdgesInShape(final GHIntHashSet edgeIds, final Shape shape, EdgeFilter filter) {
        GHPoint center = shape.getCenter();
        QueryResult qr = locationIndex.findClosest(center.getLat(), center.getLon(), filter);
        // TODO: this is suboptimal and will be fixed in #1324
        if (!qr.isValid())
            throw new IllegalArgumentException("Shape '" + shape + "' does not cover graph. Center: " + center);

        if (shape.contains(qr.getSnappedPoint().lat, qr.getSnappedPoint().lon))
            edgeIds.add(qr.getClosestEdge().getEdge());

        final boolean isPolygon = shape instanceof Polygon;

        BreadthFirstSearch bfs = new BreadthFirstSearch() {
            final NodeAccess na = graph.getNodeAccess();
            final Shape localShape = shape;

            @Override
            protected GHBitSet createBitSet() {
                return new GHTBitSet();
            }

            @Override
            protected boolean goFurther(int nodeId) {
                if (isPolygon) return isInsideBBox(nodeId);

                return localShape.contains(na.getLatitude(nodeId), na.getLongitude(nodeId));
            }

            @Override
            protected boolean checkAdjacent(EdgeIteratorState edge) {
                int adjNodeId = edge.getAdjNode();

                if (localShape.contains(na.getLatitude(adjNodeId), na.getLongitude(adjNodeId))) {
                    edgeIds.add(edge.getEdge());
                    return true;
                }
                return isPolygon && isInsideBBox(adjNodeId);
            }

            private boolean isInsideBBox(int nodeId) {
                BBox bbox = localShape.getBounds();
                double lat = na.getLatitude(nodeId);
                double lon = na.getLongitude(nodeId);
                return lat <= bbox.maxLat && lat >= bbox.minLat && lon <= bbox.maxLon && lon >= bbox.minLon;
            }
        };
        bfs.start(graph.createEdgeExplorer(filter), qr.getClosestNode());
    }

    /**
     * This method fills the edgeIds hash with edgeIds found inside the specified geometry
     */
    public void fillEdgeIDs(GHIntHashSet edgeIds, Geometry geometry, EdgeFilter filter) {
        if (geometry instanceof Point) {
            GHPoint point = GHPoint.create((Point) geometry);
            findClosestEdgeToPoint(edgeIds, point, filter);
        } else if (geometry instanceof LineString) {
            PointList pl = PointList.fromLineString((LineString) geometry);
            // TODO do map matching or routing
            int lastIdx = pl.size() - 1;
            if (pl.size() >= 2) {
                double meanLat = (pl.getLatitude(0) + pl.getLatitude(lastIdx)) / 2;
                double meanLon = (pl.getLongitude(0) + pl.getLongitude(lastIdx)) / 2;
                findClosestEdge(edgeIds, meanLat, meanLon, filter);
            }
        } else if (geometry instanceof MultiPoint) {
            for (Coordinate coordinate : geometry.getCoordinates()) {
                findClosestEdge(edgeIds, coordinate.y, coordinate.x, filter);
            }
        }
    }

    /**
     * This method reads the blockAreaString and creates a Collection of Shapes or a set of found edges if area is small enough.
     *
     * @param useEdgeIdsUntilAreaSize until the specified area (specified in m²) use the findEdgesInShape method
     */
    public BlockArea parseBlockArea(String blockAreaString, EdgeFilter filter, double useEdgeIdsUntilAreaSize) {
        final String objectSeparator = ";";
        final String innerObjSep = ",";
        BlockArea blockArea = new BlockArea(graph);

        // Add blocked circular areas or points
        if (!blockAreaString.isEmpty()) {
            String[] blockedCircularAreasArr = blockAreaString.split(objectSeparator);
            for (int i = 0; i < blockedCircularAreasArr.length; i++) {
                String objectAsString = blockedCircularAreasArr[i];
                String[] splittedObject = objectAsString.split(innerObjSep);

                // always add the shape as we'll need this for virtual edges and for debugging.
                if (splittedObject.length > 4) {
                    final Polygon polygon = Polygon.parsePoints(objectAsString);
                    blockArea.add(polygon);
                    if (polygon.calculateArea() <= useEdgeIdsUntilAreaSize)
                        findEdgesInShape(blockArea.blockedEdges, polygon, filter);
                } else if (splittedObject.length == 4) {
                    final BBox bbox = BBox.parseTwoPoints(objectAsString);
                    final RectangleLineIntersector cachedIntersector = new RectangleLineIntersector(toEnvelope(bbox));
                    BBox preparedBBox = new BBox(bbox.minLon, bbox.maxLon, bbox.minLat, bbox.maxLat) {
                        @Override
                        public boolean intersects(PointList pointList) {
                            return BBox.intersects(cachedIntersector, pointList);
                        }
                    };
                    blockArea.add(preparedBBox);
                    if (bbox.calculateArea() <= useEdgeIdsUntilAreaSize)
                        findEdgesInShape(blockArea.blockedEdges, preparedBBox, filter);
                } else if (splittedObject.length == 3) {
                    double lat = Double.parseDouble(splittedObject[0]);
                    double lon = Double.parseDouble(splittedObject[1]);
                    int radius = Integer.parseInt(splittedObject[2]);
                    Circle circle = new Circle(lat, lon, radius);
                    blockArea.add(circle);
                    if (circle.calculateArea() <= useEdgeIdsUntilAreaSize)
                        findEdgesInShape(blockArea.blockedEdges, circle, filter);

                } else if (splittedObject.length == 2) {
                    double lat = Double.parseDouble(splittedObject[0]);
                    double lon = Double.parseDouble(splittedObject[1]);
                    findClosestEdge(blockArea.blockedEdges, lat, lon, filter);
                } else {
                    throw new IllegalArgumentException(objectAsString + " at index " + i + " need to be defined as lat,lon "
                            + "or as a circle lat,lon,radius or rectangular lat1,lon1,lat2,lon2");
                }
            }
        }
        return blockArea;
    }

    /**
     * This class handles edges and areas where access should be blocked.
     */
    public static class BlockArea {
        final GHIntHashSet blockedEdges = new GHIntHashSet();
        final List<Shape> blockedShapes = new ArrayList<>();
        private final NodeAccess na;

        public BlockArea(Graph g) {
            na = g.getNodeAccess();
        }

        public void add(int edgeId) {
            blockedEdges.addAll(edgeId);
        }

        public void add(Shape shape) {
            blockedShapes.add(shape);
        }

        /**
         * @return true if the specified edgeState is part of this BlockArea
         */
        public final boolean intersects(EdgeIteratorState edgeState) {
            if (!blockedEdges.isEmpty() && blockedEdges.contains(edgeState.getEdge())) {
                return true;
            }

            // compromise: mostly avoid expensive fetchWayGeometry which isn't yet fast for being used in Weighting.calc
            BBox bbox = BBox.fromPoints(na.getLatitude(edgeState.getBaseNode()), na.getLongitude(edgeState.getBaseNode()),
                    na.getLatitude(edgeState.getAdjNode()), na.getLongitude(edgeState.getAdjNode()));
            PointList pointList = null;
            for (Shape shape : blockedShapes) {
                if (shape.getBounds().intersects(bbox)) {
                    if (pointList == null)
                        pointList = edgeState.fetchWayGeometry(3).makeImmutable();
                    if (shape.intersects(pointList))
                        return true;
                }
            }
            return false;
        }
    }
}