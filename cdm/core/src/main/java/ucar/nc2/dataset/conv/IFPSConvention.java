/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package ucar.nc2.dataset.conv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.ma2.Index;
import ucar.ma2.InvalidRangeException;
import ucar.ma2.Range;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;
import ucar.nc2.constants.AxisType;
import ucar.nc2.constants.CDM;
import ucar.nc2.constants._Coordinate;
import ucar.nc2.dataset.CoordSysBuilder;
import ucar.nc2.dataset.CoordinateAxis;
import ucar.nc2.dataset.CoordinateAxis1D;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.dataset.ProjectionCT;
import ucar.nc2.dataset.VariableDS;
import ucar.nc2.util.CancelTask;
import ucar.unidata.geoloc.LatLonPoint;
import ucar.unidata.geoloc.Projection;
import ucar.unidata.geoloc.ProjectionPoint;
import ucar.unidata.geoloc.projection.LambertConformal;

/**
 * IFPS Convention Allows Local NWS forecast office generated forecast datasets to be brought into IDV.
 * 
 * @author Burks
 */

public class IFPSConvention extends CoordSysBuilder {

  /**
   * @param ncfile the NetcdfFile to test
   * @return true if we think this is a IFPSConvention file.
   */
  public static boolean isMine(NetcdfFile ncfile) {
    // check that file has a latitude and longitude variable, and that latitude has an attribute called
    // projectionType
    boolean geoVarsCheck;
    Variable v = ncfile.findVariable("latitude");
    if (null != ncfile.findVariable("longitude") && (null != v)) {
      geoVarsCheck = null != ncfile.findAttValueIgnoreCase(v, "projectionType", null);
    } else {
      // bail early
      return false;
    }

    // check that there is a global attribute called fileFormatVersion, and that it has one
    // of two known values
    boolean fileFormatCheck;
    Attribute ff = ncfile.findGlobalAttributeIgnoreCase("fileFormatVersion");
    if (ff != null) {
      String ffValue = ff.getStringValue();
      // two possible values (as of now)
      fileFormatCheck = (ffValue.equalsIgnoreCase("20030117") || ffValue.equalsIgnoreCase("20010816"));
    } else {
      // bail
      return false;
    }

    // both must be true
    return (geoVarsCheck && fileFormatCheck);
  }

  private Variable projVar; // use this to get projection info

  public IFPSConvention() {
    this.conventionName = "IFPS";
  }

  public void augmentDataset(NetcdfDataset ds, CancelTask cancelTask) throws IOException {
    if (null != ds.findVariable("xCoord"))
      return; // check if its already been done - aggregating enhanced datasets.

    parseInfo.format("IFPS augmentDataset %n");

    // Figure out projection info. Assume the same for all variables
    VariableDS lonVar = (VariableDS) ds.findVariable("longitude");
    lonVar.setUnitsString(CDM.LON_UNITS);
    lonVar.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.Lon.toString()));
    VariableDS latVar = (VariableDS) ds.findVariable("latitude");
    latVar.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.Lat.toString()));
    latVar.setUnitsString(CDM.LAT_UNITS);

    projVar = latVar;
    String projName = ds.findAttValueIgnoreCase(projVar, "projectionType", null);
    if ("LAMBERT_CONFORMAL".equals(projName)) {
      Projection proj = makeLCProjection(ds);
      makeXYcoords(ds, proj, latVar, lonVar);
    }

    // figure out the time coordinate for each data variable
    // LOOK : always seperate; could try to discover if they are the same
    List<Variable> vars = ds.getVariables();
    for (Variable ncvar : vars) {
      // variables that are used but not displayable or have no data have DIM_0, also don't want history, since those
      // are just how the person edited the grids
      if ((!ncvar.getDimension(0).getShortName().equals("DIM_0")) && !ncvar.getShortName().endsWith("History")
          && (ncvar.getRank() > 2) && !ncvar.getShortName().startsWith("Tool")) {
        createTimeCoordinate(ds, ncvar);
      } else if (ncvar.getShortName().equals("Topo")) {
        // Deal with Topography variable
        ncvar.addAttribute(new Attribute(CDM.LONG_NAME, "Topography"));
        ncvar.addAttribute(new Attribute(CDM.UNITS, "ft"));
      }
    }

    ds.finish();
  }

  private void createTimeCoordinate(NetcdfDataset ds, Variable ncVar) {
    // Time coordinate is stored in the attribute validTimes
    // One caveat is that the times have two bounds an upper and a lower

    // get the times values
    Attribute timesAtt = ncVar.findAttribute("validTimes");
    if (timesAtt == null)
      return;
    Array timesArray = timesAtt.getValues();

    // get every other one LOOK this is awkward
    try {
      int n = (int) timesArray.getSize();
      List<Range> list = new ArrayList<>();
      list.add(new Range(0, n - 1, 2));
      timesArray = timesArray.section(list);
    } catch (InvalidRangeException e) {
      throw new IllegalStateException(e);
    }

    // make sure it matches the dimension
    DataType dtype = DataType.getType(timesArray);
    int nTimesAtt = (int) timesArray.getSize();

    // create a special dimension and coordinate variable
    Dimension dimTime = ncVar.getDimension(0);
    int nTimesDim = dimTime.getLength();
    if (nTimesDim != nTimesAtt) {
      parseInfo.format(" **error ntimes in attribute (%d) doesnt match dimension length (%d) for variable %s%n",
          nTimesAtt, nTimesDim, ncVar.getFullName());
      return;
    }

    // add the dimension
    String dimName = ncVar.getFullName() + "_timeCoord";
    Dimension newDim = new Dimension(dimName, nTimesDim);
    ds.addDimension(null, newDim);

    // add the coordinate variable
    String units = "seconds since 1970-1-1 00:00:00";
    String desc = "time coordinate for " + ncVar.getFullName();

    CoordinateAxis1D timeCoord = new CoordinateAxis1D(ds, null, dimName, dtype, dimName, units, desc);
    timeCoord.setCachedData(timesArray, true);
    timeCoord.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.Time.toString()));
    ds.addCoordinateAxis(timeCoord);

    parseInfo.format(" added coordinate variable %s%n", dimName);

    // now make the original variable use the new dimension
    List<Dimension> dimsList = new ArrayList(ncVar.getDimensions());
    dimsList.set(0, newDim);
    ncVar.setDimensions(dimsList);

    // better to explicitly set the coordinate system
    ncVar.addAttribute(new Attribute(_Coordinate.Axes, dimName + " yCoord xCoord"));

    // fix the attributes
    Attribute att = ncVar.findAttribute("fillValue");
    if (att != null)
      ncVar.addAttribute(new Attribute(CDM.FILL_VALUE, att.getNumericValue()));
    att = ncVar.findAttribute("descriptiveName");
    if (null != att)
      ncVar.addAttribute(new Attribute(CDM.LONG_NAME, att.getStringValue()));

    // ncVar.enhance();
  }

  protected String getZisPositive(NetcdfDataset ds, CoordinateAxis v) {
    return "up";
  }

  private Projection makeLCProjection(NetcdfDataset ds) {
    Attribute latLonOrigin = projVar.attributes().findAttributeIgnoreCase("latLonOrigin");
    if (latLonOrigin == null || latLonOrigin.isString())
      throw new IllegalStateException();
    double centralLon = latLonOrigin.getNumericValue(0).doubleValue();
    double centralLat = latLonOrigin.getNumericValue(1).doubleValue();

    double par1 = findAttributeDouble("stdParallelOne");
    double par2 = findAttributeDouble("stdParallelTwo");
    LambertConformal lc = new LambertConformal(centralLat, centralLon, par1, par2);

    // make Coordinate Transform Variable
    ProjectionCT ct = new ProjectionCT("lambertConformalProjection", "FGDC", lc);
    VariableDS ctVar = makeCoordinateTransformVariable(ds, ct);
    ctVar.addAttribute(new Attribute(_Coordinate.Axes, "xCoord yCoord"));
    ds.addVariable(null, ctVar);

    return lc;
  }

  private void makeXYcoords(NetcdfDataset ds, Projection proj, Variable latVar, Variable lonVar) throws IOException {
    // brute force
    Array latData = latVar.read();
    Array lonData = lonVar.read();

    Dimension y_dim = latVar.getDimension(0);
    Dimension x_dim = latVar.getDimension(1);

    Array xData = Array.factory(DataType.FLOAT, new int[] {x_dim.getLength()});
    Array yData = Array.factory(DataType.FLOAT, new int[] {y_dim.getLength()});

    Index latlonIndex = latData.getIndex();
    Index xIndex = xData.getIndex();
    Index yIndex = yData.getIndex();

    // construct x coord
    for (int i = 0; i < x_dim.getLength(); i++) {
      double lat = latData.getDouble(latlonIndex.set1(i));
      double lon = lonData.getDouble(latlonIndex);
      LatLonPoint latlon = LatLonPoint.create(lat, lon);
      ProjectionPoint pp = proj.latLonToProj(latlon);
      xData.setDouble(xIndex.set(i), pp.getX());
    }

    // construct y coord
    for (int i = 0; i < y_dim.getLength(); i++) {
      double lat = latData.getDouble(latlonIndex.set0(i));
      double lon = lonData.getDouble(latlonIndex);
      LatLonPoint latlon = LatLonPoint.create(lat, lon);
      ProjectionPoint pp = proj.latLonToProj(latlon);
      yData.setDouble(yIndex.set(i), pp.getY());
    }

    VariableDS xaxis =
        new VariableDS(ds, null, null, "xCoord", DataType.FLOAT, x_dim.getShortName(), "km", "x on projection");
    xaxis.addAttribute(new Attribute(CDM.UNITS, "km"));
    xaxis.addAttribute(new Attribute(CDM.LONG_NAME, "x on projection"));
    xaxis.addAttribute(new Attribute(_Coordinate.AxisType, "GeoX"));

    VariableDS yaxis =
        new VariableDS(ds, null, null, "yCoord", DataType.FLOAT, y_dim.getShortName(), "km", "y on projection");
    yaxis.addAttribute(new Attribute(CDM.UNITS, "km"));
    yaxis.addAttribute(new Attribute(CDM.LONG_NAME, "y on projection"));
    yaxis.addAttribute(new Attribute(_Coordinate.AxisType, "GeoY"));

    xaxis.setCachedData(xData, true);
    yaxis.setCachedData(yData, true);

    ds.addVariable(null, xaxis);
    ds.addVariable(null, yaxis);
  }

  private double findAttributeDouble(String attname) {
    Attribute att = projVar.attributes().findAttributeIgnoreCase(attname);
    return (att == null || att.isString()) ? Double.NaN : att.getNumericValue().doubleValue();
  }

}
