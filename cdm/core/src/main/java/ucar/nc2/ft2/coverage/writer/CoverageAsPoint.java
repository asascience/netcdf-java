/*
 * Copyright (c) 1998-2020 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.nc2.ft2.coverage.writer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;
import ucar.ma2.Array;
import ucar.ma2.IndexIterator;
import ucar.ma2.InvalidRangeException;
import ucar.ma2.StructureData;
import ucar.ma2.StructureDataFromMember;
import ucar.ma2.StructureMembers;
import ucar.nc2.VariableSimpleBuilder;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.constants.FeatureType;
import ucar.nc2.ft.FeatureDatasetPoint;
import ucar.nc2.ft.PointFeature;
import ucar.nc2.ft.PointFeatureIterator;
import ucar.nc2.ft.point.PointFeatureImpl;
import ucar.nc2.ft.point.PointIteratorAbstract;
import ucar.nc2.ft.point.StationFeature;
import ucar.nc2.ft.point.StationHelper;
import ucar.nc2.ft.point.StationPointFeature;
import ucar.nc2.ft.point.StationTimeSeriesCollectionImpl;
import ucar.nc2.ft.point.StationTimeSeriesFeatureImpl;
import ucar.nc2.ft2.coverage.Coverage;
import ucar.nc2.ft2.coverage.CoverageCollection;
import ucar.nc2.ft2.coverage.CoverageCoordAxis;
import ucar.nc2.ft2.coverage.CoverageCoordAxis1D;
import ucar.nc2.ft2.coverage.CoverageCoordSys;
import ucar.nc2.ft2.coverage.GeoReferencedArray;
import ucar.nc2.ft2.coverage.SubsetParams;
import ucar.nc2.time.CalendarDateUnit;
import ucar.unidata.geoloc.LatLonPoint;
import ucar.unidata.geoloc.LatLonPoints;
import ucar.unidata.util.StringUtil2;

/**
 * Write DSG CF-1.6 file from a Coverage Dataset
 *
 * @author caron
 * @since 7/8/2015
 */
public class CoverageAsPoint {
  private static final boolean debug = false;

  private CoverageCollection gcd;
  private List<VarData> varData;
  private SubsetParams subset;
  private LatLonPoint latLonPoint;
  private LatLonPoint nearestLatLonPoint;
  private CalendarDateUnit dateUnit;

  private class VarData {
    Coverage cov;
    GeoReferencedArray array;

    VarData(Coverage cov) throws IOException {
      this.cov = cov;
      try {
        this.array = cov.readData(subset);
        if (debug)
          System.out.printf(" Coverage %s data shape = %s%n", cov.getName(),
              Arrays.toString(array.getData().getShape()));
      } catch (InvalidRangeException e) {
        e.printStackTrace();
      }
    }
  }

  public CoverageAsPoint(CoverageCollection gcd, List<String> varNames, SubsetParams subset) throws IOException {
    this.gcd = gcd;
    this.subset = subset;

    latLonPoint = (LatLonPoint) subset.get(SubsetParams.latlonPoint);
    if (latLonPoint == null)
      throw new IllegalArgumentException("No latlon point");

    varData = new ArrayList<>(varNames.size());
    for (String varName : varNames) {
      Coverage cov = gcd.findCoverage(varName); // LOOK we should read all at once if possible
      if (cov != null) {
        varData.add(new VarData(cov));

        if (dateUnit == null) { // assume all have the same time unit, just use the first one
          CoverageCoordSys csys = cov.getCoordSys();
          CoverageCoordAxis timeAxis = csys.getTimeAxis();
          this.dateUnit = timeAxis.getCalendarDateUnit();
        }
        if (nearestLatLonPoint == null) {
          CoverageCoordSys subsetCs = varData.get(0).array.getCoordSysForData();
          // single point subset, so only one lat/lon to grab, and this will be the lat/lon
          // closest to the one requested in the subset
          nearestLatLonPoint = subsetCs.getHorizCoordSys().getLatLon(0, 0);
        }
      }
    }
  }

  public FeatureDatasetPoint asFeatureDatasetPoint() {
    // for the moment, assume a single station, no vert coord, single lat/lon
    return new CoverageAsFeatureDatasetPoint(FeatureType.STATION);
  }

  private class CoverageAsFeatureDatasetPoint extends ucar.nc2.ft.point.PointDatasetImpl {
    CoverageAsFeatureDatasetPoint(FeatureType featureType) {
      super(featureType);
      CoverageAsStationFeatureCollection fc =
          new CoverageAsStationFeatureCollection(gcd.getName() + " AsStationFeatureCollection", dateUnit, null);
      setPointFeatureCollection(fc);

      List<VariableSimpleIF> dataVars = new ArrayList<>();
      for (VarData vd : varData) {
        VariableSimpleIF simple = VariableSimpleBuilder
            .makeScalar(vd.cov.getName(), vd.cov.getDescription(), vd.cov.getUnitsString(), vd.cov.getDataType())
            .build();
        dataVars.add(simple);
      }
      this.dataVariables = dataVars;
    }
  }

  private class CoverageAsStationFeatureCollection extends StationTimeSeriesCollectionImpl {

    CoverageAsStationFeatureCollection(String name, CalendarDateUnit dateUnit, String altUnits) {
      super(name, dateUnit, altUnits);
    }

    @Override
    protected StationHelper createStationHelper() {
      StationHelper helper = new StationHelper();
      String name = String.format("GridPointRequestedAt[%s]", LatLonPoints.toString(latLonPoint, 3));
      name = StringUtil2.replace(name.trim(), ' ', "_");
      helper.addStation(new MyStationFeature(name, name, null, nearestLatLonPoint.getLatitude(),
          nearestLatLonPoint.getLongitude(), 0.0, dateUnit, null, -1));
      return helper;
    }

  }

  private class MyStationFeature extends StationTimeSeriesFeatureImpl {

    MyStationFeature(String name, String desc, String wmoId, double lat, double lon, double alt,
        CalendarDateUnit timeUnit, String altUnits, int npts) {
      // String name, String desc, String wmoId, double lat, double lon, double alt, DateUnit timeUnit, String altUnits,
      // int npts
      super(name, desc, wmoId, lat, lon, alt, timeUnit, altUnits, npts, StructureData.EMPTY);
    }

    @Nonnull
    @Override
    public StructureData getFeatureData() {
      return StructureData.EMPTY;
    }

    @Override
    public PointFeatureIterator getPointFeatureIterator() {
      return new TimeseriesIterator();
    }

    private class VarIter {
      Coverage cov;
      GeoReferencedArray geoA;
      IndexIterator dataIter;

      VarIter(Coverage cov, GeoReferencedArray array, IndexIterator dataIter) {
        this.cov = cov;
        this.geoA = array;
        this.dataIter = dataIter;
      }
    }

    private class TimeseriesIterator extends PointIteratorAbstract {
      int curr;
      int nvalues;
      List<VarIter> varIters;
      CoverageCoordAxis1D timeAxis;

      TimeseriesIterator() {
        varIters = new ArrayList<>();
        for (VarData vd : varData) {
          Array data = vd.array.getData();
          if (debug)
            System.out.printf("%s shape=%s%n", vd.cov.getName(), Arrays.toString(data.getShape()));
          varIters.add(new VarIter(vd.cov, vd.array, data.getIndexIterator()));
          nvalues = (int) data.getSize();

          if (timeAxis == null) { // assume they are all the same (!)
            CoverageCoordSys csys = vd.array.getCoordSysForData();
            timeAxis = (CoverageCoordAxis1D) csys.getTimeAxis(); // LOOK may not be right
          }
        }
      }

      @Override
      public boolean hasNext() {
        boolean more = curr < nvalues;
        if (!more)
          close();
        return more;
      }

      @Override
      public PointFeature next() {
        double obsTime = timeAxis.getCoordMidpoint(curr);

        StructureMembers.Builder smb = StructureMembers.builder().setName("Coords");
        for (VarIter vi : varIters) {
          smb.addMemberScalar(vi.cov.getName(), null, null, vi.cov.getDataType(), (Number) vi.dataIter.getObjectNext());
        }
        StructureData coords = new StructureDataFromMember(smb.build());
        curr++;
        PointFeature pf = new MyPointFeature(MyStationFeature.this, obsTime, 0.0, timeUnit, coords);
        calcBounds(pf);
        return pf;
      }

      @Override
      public void close() {
        finishCalcBounds();
      }
    }

    private class MyPointFeature extends PointFeatureImpl implements StationPointFeature {
      StationFeature stn;
      StructureData sdata;

      MyPointFeature(StationFeature stn, double obsTime, double nomTime, CalendarDateUnit timeUnit,
          StructureData sdata) {
        super(MyStationFeature.this, stn, obsTime, nomTime, timeUnit);
        this.stn = stn;
        this.sdata = sdata;
      }

      @Override
      @Nonnull
      public StationFeature getStation() {
        return stn;
      }

      @Override
      @Nonnull
      public StructureData getFeatureData() {
        return sdata;
      }

      @Override
      @Nonnull
      public StructureData getDataAll() {
        return sdata;
      }
    }

  }

}
