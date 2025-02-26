/*
 * Copyright (c) 1998-2020 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.nc2.dataset.conv;

import com.google.common.collect.ImmutableMap;
import ucar.nc2.*;
import ucar.nc2.constants.CDM;
import ucar.nc2.constants._Coordinate;
import ucar.nc2.constants.AxisType;
import ucar.nc2.constants.CF;
import ucar.nc2.time.CalendarDateUnit;
import ucar.nc2.units.SimpleUnit;
import ucar.nc2.util.CancelTask;
import ucar.nc2.dataset.*;
import ucar.ma2.Array;
import ucar.ma2.IndexIterator;
import ucar.ma2.DataType;
import java.util.*;
import java.io.IOException;

/**
 * CF-1 Convention. see http://www.cgd.ucar.edu/cms/eaton/cf-metadata/index.html
 * <p/>
 * <i>
 * "The CF conventions for climate and forecast metadata are designed to promote the processing and sharing of files
 * created with the netCDF
 * API. The conventions define metadata that provide a definitive description of what the data in each variable
 * represents, and of the
 * spatial and temporal properties of the data. This enables users of data from different sources to decide which
 * quantities are comparable,
 * and facilitates building applications with powerful extraction, regridding, and display capabilities."
 * </i>
 *
 * @author caron
 */

public class CF1Convention extends CSMConvention {

  private static final String convName = "CF-1."; // start with

  /**
   * Get which CF version this is, ie CF-1.x
   *
   * @param hasConvName extract from convention name or list of names
   * @return version, or -1 if not CF
   */
  public static int getVersion(String hasConvName) {
    int result = extractVersion(hasConvName);
    if (result >= 0) {
      return result;
    }
    List<String> names = breakupConventionNames(hasConvName);
    for (String name : names) {
      result = extractVersion(name);
      if (result >= 0) {
        return result;
      }
    }
    return -1;
  }

  private static int extractVersion(String hasConvName) {
    if (!hasConvName.startsWith(convName)) {
      return -1;
    }
    String versionS = hasConvName.substring(convName.length());
    try {
      return Integer.parseInt(versionS);
    } catch (Exception e) {
      return -1;
    }
  }


  /**
   * Guess the value of ZisPositive based on z axis name and units
   *
   * @param zaxisName z coordinate axis name
   * @param vertCoordUnits z coordinate axis name
   * @return CF.POSITIVE_UP or CF.POSITIVE_DOWN
   */
  public static String getZisPositive(String zaxisName, String vertCoordUnits) {
    if (vertCoordUnits == null) {
      return CF.POSITIVE_UP;
    }
    if (vertCoordUnits.isEmpty()) {
      return CF.POSITIVE_UP;
    }

    if (SimpleUnit.isCompatible("millibar", vertCoordUnits)) {
      return CF.POSITIVE_DOWN;
    }

    if (SimpleUnit.isCompatible("m", vertCoordUnits)) {
      return CF.POSITIVE_UP;
    }

    // dunno - make it up
    return CF.POSITIVE_UP;
  }

  private static final String[] vertical_coords = {"atmosphere_ln_pressure_coordinate", "atmosphere_sigma_coordinate",
      "atmosphere_hybrid_sigma_pressure_coordinate", "atmosphere_hybrid_height_coordinate",
      "atmosphere_sleve_coordinate", "ocean_sigma_coordinate", "ocean_s_coordinate", "ocean_sigma_z_coordinate",
      "ocean_double_sigma_coordinate", "ocean_s_coordinate_g1", // -sachin 03/25/09
      "ocean_s_coordinate_g2"};

  public CF1Convention() {
    this.conventionName = "CF-1.X";
  }

  public void augmentDataset(NetcdfDataset ds, CancelTask cancelTask) throws IOException {
    boolean got_grid_mapping = false;

    // look for transforms
    List<Variable> vars = ds.getVariables();
    for (Variable v : vars) {
      // look for special standard_names
      String sname = ds.findAttValueIgnoreCase(v, CF.STANDARD_NAME, null);
      if (sname != null) {
        sname = sname.trim();

        if (sname.equalsIgnoreCase(CF.atmosphere_ln_pressure_coordinate)) { // LOOK why isnt this with other Transforms?
          makeAtmLnCoordinate(ds, v);
          continue;
        }

        if (sname.equalsIgnoreCase(CF.TIME_REFERENCE)) {
          v.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.RunTime.toString()));
          continue;
        }

        if (sname.equalsIgnoreCase(CF.TIME_OFFSET)) {
          v.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.TimeOffset.toString()));
          continue;
        }

        if (sname.equalsIgnoreCase(CF.TIME)) {
          v.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.Time.toString()));
        }

        if (sname.equalsIgnoreCase("ensemble") || sname.equalsIgnoreCase("realization")) {
          v.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.Ensemble.toString()));
          continue;
        }

        for (String vertical_coord : vertical_coords) {
          if (sname.equalsIgnoreCase(vertical_coord)) {
            v.addAttribute(new Attribute(_Coordinate.TransformType, TransformType.Vertical.toString()));
            if (v.findAttribute(_Coordinate.Axes) == null) {
              v.addAttribute(new Attribute(_Coordinate.Axes, v.getFullName())); // LOOK: may also be time dependent
            }
          }
        }

        // look for time variables and check to see if they have a calendar attribute. if not, add the default
        checkTimeVarForCalendar(v);
      }

      // look for horiz transforms. only ones that are referenced by another variable.
      String grid_mapping = ds.findAttValueIgnoreCase(v, CF.GRID_MAPPING, null);
      if (grid_mapping != null) {
        Variable gridMap = ds.findVariable(grid_mapping);
        if (gridMap == null) {
          Group g = v.getParentGroupOrRoot(); // might be group relative - CF does not specify
          gridMap = g.findVariableLocal(grid_mapping);
        }

        if (gridMap != null) {
          gridMap.addAttribute(new Attribute(_Coordinate.TransformType, TransformType.Projection.toString()));

          String grid_mapping_name = ds.findAttValueIgnoreCase(gridMap, CF.GRID_MAPPING_NAME, null);
          if (CF.LATITUDE_LONGITUDE.equals(grid_mapping_name)) {
            // "grid_mapping_name == latitude_longitude" is special in CF: it's applied to variables that describe
            // properties of lat/lon CRSes.
            gridMap.addAttribute(new Attribute(_Coordinate.AxisTypes, AxisType.Lat + " " + AxisType.Lon));
          } else {
            gridMap.addAttribute(new Attribute(_Coordinate.AxisTypes, AxisType.GeoX + " " + AxisType.GeoY));
          }
          // check for CF-ish GOES-16/17 grid mappings
          Attribute productionLocation = ds.findGlobalAttributeIgnoreCase("production_location");
          Attribute icdVersion = ds.findGlobalAttributeIgnoreCase("ICD_version");
          if (productionLocation != null && icdVersion != null) {
            // the fact that those two global attributes are not null means we should check to see
            // if the grid mapping variable has attributes that need corrected.
            correctGoes16(productionLocation, icdVersion, gridMap);
          }
          got_grid_mapping = true;
        }
      }

      // simple geometry

      if (ds.findGlobalAttribute(CF.CONVENTIONS) != null) {
        if (getVersion(ds.findGlobalAttribute(CF.CONVENTIONS).getStringValue()) >= 8) // only acknowledge simple
                                                                                      // geometry standard extension if
                                                                                      // CF-1.8 or higher
        {
          if (v.findAttribute(CF.GEOMETRY) != null) {

            Attribute container = v.findAttribute(CF.GEOMETRY);
            Variable coordsvar = ds.findVariable(container.getStringValue());

            v.addAttribute(new Attribute(CF.GEOMETRY_TYPE, ds.findAttValueIgnoreCase(coordsvar, CF.GEOMETRY_TYPE, "")));

            // Only add attribute if present, sometimes optional
            if (!ds.findAttValueIgnoreCase(coordsvar, CF.NODES, "").equals("")) {
              v.addAttribute(new Attribute(CF.NODES, ds.findAttValueIgnoreCase(coordsvar, CF.NODES, "")));
            }

            // Only add attribute if present, sometimes optional
            if (!ds.findAttValueIgnoreCase(coordsvar, CF.NODE_COUNT, "").equals("")) {
              v.addAttribute(new Attribute(CF.NODE_COUNT, ds.findAttValueIgnoreCase(coordsvar, CF.NODE_COUNT, "")));
            }

            v.addAttribute(
                new Attribute(CF.NODE_COORDINATES, ds.findAttValueIgnoreCase(coordsvar, CF.NODE_COORDINATES, "")));
            v.addAttribute(
                new Attribute(CF.PART_NODE_COUNT, ds.findAttValueIgnoreCase(coordsvar, CF.PART_NODE_COUNT, "")));
            if (CF.POLYGON.equalsIgnoreCase(ds.findAttValueIgnoreCase(coordsvar, CF.GEOMETRY_TYPE, ""))) {

              // Again, interior ring is not always required, but add it if it is present
              if (!ds.findAttValueIgnoreCase(coordsvar, CF.INTERIOR_RING, "").equals("")) {
                v.addAttribute(
                    new Attribute(CF.INTERIOR_RING, ds.findAttValueIgnoreCase(coordsvar, CF.INTERIOR_RING, "")));
              }
            }

            if (v.findAttribute(CF.NODE_COORDINATES) != null) {

              String[] coords = ds.findAttValueIgnoreCase(coordsvar, CF.NODE_COORDINATES, "").split(" ");
              String cds = "";
              for (String coord : coords) {
                Variable temp = ds.findVariable(coord);
                if (temp != null) {
                  Attribute axis = temp.findAttribute(CF.AXIS);
                  if (axis != null) {
                    if ("x".equalsIgnoreCase(axis.getStringValue())) {
                      temp.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.SimpleGeometryX.toString()));
                    }
                    if ("y".equalsIgnoreCase(axis.getStringValue())) {
                      temp.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.SimpleGeometryY.toString()));
                    }
                    if ("z".equalsIgnoreCase(axis.getStringValue())) {
                      temp.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.SimpleGeometryZ.toString()));
                    }

                    cds += coord + " ";

                  }
                }
              }

              List<Dimension> dims = v.getDimensions();

              // Append any geometry dimensions as axis
              String pre = "";

              for (Dimension di : dims) {

                if (!di.getShortName().equals("time")) {
                  if (ds.findVariable(di.getFullNameEscaped()) != null) {
                    ds.findVariable(di.getFullNameEscaped())
                        .addAttribute(new Attribute(_Coordinate.AxisType, AxisType.SimpleGeometryID.toString()));
                  }
                  // handle else case as malformed CF NetCDF
                }

                pre = di.getShortName() + " " + pre;
              }

              v.addAttribute(new Attribute(_Coordinate.Axes, pre + cds.trim()));
            }
          }
        }
      }
    }

    if (!got_grid_mapping) { // see if there are any grid mappings anyway
      for (Variable v : ds.getVariables()) {
        String grid_mapping_name = ds.findAttValueIgnoreCase(v, CF.GRID_MAPPING_NAME, null);
        if (grid_mapping_name != null) {
          v.addAttribute(new Attribute(_Coordinate.TransformType, TransformType.Projection.toString()));

          if (grid_mapping_name.equals(CF.LATITUDE_LONGITUDE)) {
            v.addAttribute(new Attribute(_Coordinate.AxisTypes, AxisType.Lat + " " + AxisType.Lon));
          } else {
            v.addAttribute(new Attribute(_Coordinate.AxisTypes, AxisType.GeoX + " " + AxisType.GeoY));
          }
        }
      }
    }

    // make corrections for specific datasets
    String src = ds.findAttValueIgnoreCase(null, "Source", "");
    if (src.equals("NOAA/National Climatic Data Center")) {
      String title = ds.findAttValueIgnoreCase(null, "title", "");
      avhrr_oiv2 = title.indexOf("OI-V2") > 0;
    }
    ds.finish();
  }

  private boolean avhrr_oiv2;

  // this is here because it doesnt fit into the 3D array thing.
  private void makeAtmLnCoordinate(NetcdfDataset ds, Variable v) {
    // get the formula attribute
    String formula = ds.findAttValueIgnoreCase(v, CF.formula_terms, null);
    if (null == formula) {
      String msg = " Need attribute 'formula_terms' on Variable " + v.getFullName() + "\n";
      parseInfo.format(msg);
      userAdvice.format(msg);
      return;
    }

    // parse the formula string
    Variable p0Var = null, levelVar = null;
    StringTokenizer stoke = new StringTokenizer(formula, " :");
    while (stoke.hasMoreTokens()) {
      String toke = stoke.nextToken();
      if (toke.equalsIgnoreCase("p0")) {
        String name = stoke.nextToken();
        p0Var = ds.findVariable(name);
      } else if (toke.equalsIgnoreCase("lev")) {
        String name = stoke.nextToken();
        levelVar = ds.findVariable(name);
      }
    }

    if (null == p0Var) {
      String msg = " Need p0:varName on Variable " + v.getFullName() + " formula_terms\n";
      parseInfo.format(msg);
      userAdvice.format(msg);
      return;
    }

    if (null == levelVar) {
      String msg = " Need lev:varName on Variable " + v.getFullName() + " formula_terms\n";
      parseInfo.format(msg);
      userAdvice.format(msg);
      return;
    }

    String units = ds.findAttValueIgnoreCase(p0Var, CDM.UNITS, "hPa");

    // create the data and the variable
    try { // p(k) = p0 * exp(-lev(k))
      double p0 = p0Var.readScalarDouble();
      Array levelData = levelVar.read();
      Array pressureData = Array.factory(DataType.DOUBLE, levelData.getShape());
      IndexIterator ii = levelData.getIndexIterator();
      IndexIterator iip = pressureData.getIndexIterator();
      while (ii.hasNext()) {
        double val = p0 * Math.exp(-1.0 * ii.getDoubleNext());
        iip.setDoubleNext(val);
      }

      CoordinateAxis1D p = new CoordinateAxis1D(ds, null, v.getShortName() + "_pressure", DataType.DOUBLE,
          levelVar.getDimensionsString(), units,
          "Vertical Pressure coordinate synthesized from atmosphere_ln_pressure_coordinate formula");
      p.setCachedData(pressureData, false);
      p.addAttribute(new Attribute(_Coordinate.AxisType, AxisType.Pressure.toString()));
      p.addAttribute(new Attribute(_Coordinate.AliasForDimension, p.getDimensionsString()));
      ds.addVariable(null, p);
      parseInfo.format(" added Vertical Pressure coordinate %s from CF-1 %s%n", p.getFullName(),
          CF.atmosphere_ln_pressure_coordinate);

    } catch (IOException e) {
      String msg = " Unable to read variables from " + v.getFullName() + " formula_terms\n";
      parseInfo.format(msg);
      userAdvice.format(msg);
    }

  }

  /*
   * vertical coordinate will be identifiable by:
   * 1. units of pressure; or
   * 2. the presence of the positive attribute with a value of up or down (case insensitive).
   * 3. Optionally, the vertical type may be indicated additionally by providing the standard_name attribute with an
   * appropriate value, and/or the axis attribute with the value Z.
   */

  // we assume that coordinate axes get identified by
  // 1) being coordinate variables or
  // 2) being listed in coordinates attribute.

  /**
   * Augment COARDS axis type identification with Standard names (including dimensionless vertical coordinates) and
   * CF.AXIS attributes
   */
  protected AxisType getAxisType(NetcdfDataset ncDataset, VariableEnhanced v) {
    // standard names for unitless vertical coords
    String sname = ncDataset.findAttValueIgnoreCase((Variable) v, CF.STANDARD_NAME, null);
    if (sname != null) {
      sname = sname.trim();

      for (String vertical_coord : vertical_coords) {
        if (sname.equalsIgnoreCase(vertical_coord)) {
          return AxisType.GeoZ;
        }
      }
    }

    // COARDS - check units
    AxisType at = super.getAxisType(ncDataset, v);
    if (at != null) {
      return at;
    }

    // standard names for X, Y : bug in CDO putting wrong standard name, so check units first (!)
    if (sname != null) {
      if (sname.equalsIgnoreCase(CF.ENSEMBLE)) {
        return AxisType.Ensemble;
      }

      if (sname.equalsIgnoreCase(CF.LATITUDE)) {
        return AxisType.Lat;
      }

      if (sname.equalsIgnoreCase(CF.LONGITUDE)) {
        return AxisType.Lon;
      }

      if (sname.equalsIgnoreCase(CF.PROJECTION_X_COORDINATE) || sname.equalsIgnoreCase(CF.GRID_LONGITUDE)
          || sname.equalsIgnoreCase("rotated_longitude")) {
        return AxisType.GeoX;
      }

      if (sname.equalsIgnoreCase(CF.PROJECTION_Y_COORDINATE) || sname.equalsIgnoreCase(CF.GRID_LATITUDE)
          || sname.equalsIgnoreCase("rotated_latitude")) {
        return AxisType.GeoY;
      }

      if (sname.equalsIgnoreCase(CF.TIME_REFERENCE)) {
        return AxisType.RunTime;
      }

      if (sname.equalsIgnoreCase(CF.TIME_OFFSET)) {
        return AxisType.TimeOffset;
      }
    }

    // check axis attribute - only for X, Y, Z
    String axis = ncDataset.findAttValueIgnoreCase((Variable) v, CF.AXIS, null);
    if (axis != null) {
      axis = axis.trim();
      String unit = v.getUnitsString();

      if (axis.equalsIgnoreCase("X")) {
        if (SimpleUnit.isCompatible("m", unit)) {
          return AxisType.GeoX;
        }

      } else if (axis.equalsIgnoreCase("Y")) {
        if (SimpleUnit.isCompatible("m", unit)) {
          return AxisType.GeoY;
        }

      } else if (axis.equalsIgnoreCase("Z")) {
        if (unit == null) {
          return AxisType.GeoZ;
        }
        if (SimpleUnit.isCompatible("m", unit)) {
          return AxisType.Height;
        } else if (SimpleUnit.isCompatible("mbar", unit)) {
          return AxisType.Pressure;
        } else {
          return AxisType.GeoZ;
        }
      }
    }

    if (avhrr_oiv2) {
      if (v.getShortName().equals("zlev")) {
        return AxisType.Height;
      }
    }

    try {
      String units = v.getUnitsString();
      CalendarDateUnit cd = CalendarDateUnit.of(null, units);
      // parsed successfully, what could go wrong?
      return AxisType.Time;
    } catch (Throwable t) {
      // ignore
    }

    // dunno
    return null;
  }

  private void correctGoes16(Attribute productionLocation, Attribute icdVersion, Variable gridMappingVar) {
    // Files with these global attributes might need corrected
    // :ICD_version = "GROUND SEGMENT (GS) TO ADVANCED WEATHER INTERACTIVE PROCESSING SYSTEM (AWIPS) INTERFACE CONTROL
    // DOCUMENT (ICD) Revision B" ;
    // :production_location = "WCDAS" ;
    String prodLoc = productionLocation.getStringValue();
    String icdVer = icdVersion.getStringValue();
    if (prodLoc != null && icdVer != null) {
      prodLoc = prodLoc.toLowerCase().trim();
      icdVer = icdVer.toLowerCase().trim();
      boolean mightNeedCorrected = prodLoc.contains("wcdas");
      mightNeedCorrected = mightNeedCorrected && icdVer.contains("ground segment");
      mightNeedCorrected = mightNeedCorrected && icdVer.contains("awips");
      if (mightNeedCorrected) {
        Map<String, String> possibleCorrections =
            ImmutableMap.of("semi_minor", CF.SEMI_MINOR_AXIS, "semi_major", CF.SEMI_MAJOR_AXIS);
        possibleCorrections.forEach((incorrect, correct) -> {
          Attribute attr = gridMappingVar.findAttributeIgnoreCase(incorrect);
          if (attr != null) {
            Array vals = attr.getValues();
            if (vals != null) {
              gridMappingVar.addAttribute(new Attribute(correct, vals));
              gridMappingVar.remove(attr);
              log.debug("Renamed {} attribute {} to {}", gridMappingVar, incorrect, correct);
            }
          }
        });
      }
    }
  }
}

