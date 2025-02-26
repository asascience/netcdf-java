/*
 * Copyright (c) 1998-2020 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */
package ucar.nc2.internal.ncml;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.ma2.IndexIterator;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Attribute;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;
import ucar.nc2.constants.CDM;
import ucar.nc2.ncml.TestNcmlRead;
import ucar.unidata.util.test.Assert2;

public class TestNcmlModifyAtts {
  private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static NetcdfFile ncfile = null;

  @BeforeClass
  public static void setUp() throws IOException {
    String filename = "file:" + TestNcmlRead.topDir + "modifyAtts.xml";
    ncfile = NcmlReader.readNcml(filename, null, null).build();
  }

  @AfterClass
  public static void tearDown() throws IOException {
    ncfile.close();
  }

  @Test
  public void testGlobalAtt() {
    Attribute att = ncfile.findGlobalAttribute("Conventions");
    assert null != att;
    assert !att.isArray();
    assert att.isString();
    assert att.getDataType() == DataType.STRING;
    assert att.getStringValue().equals("Metapps");
    assert att.getNumericValue() == null;
    assert att.getNumericValue(3) == null;
  }

  @Test
  public void testVarAtt() {
    Variable v = ncfile.findVariable("rh");
    assert null != v;

    Attribute att = v.findAttribute(CDM.LONG_NAME);
    assert null == att;

    att = v.findAttribute("units");
    assert null != att;
    assert att.getStringValue().equals("percent");

    att = v.findAttribute("UNITS");
    assert null != att;
    assert att.getStringValue().equals("percent");

    att = v.findAttribute("longer_name");
    assert null != att;
    assert !att.isArray();
    assert att.isString();
    assert att.getDataType() == DataType.STRING;
    assert att.getStringValue().equals("Abe said what?");
  }

  @Test
  public void testStructure() {
    Attribute att = ncfile.findGlobalAttribute("title");
    assert null == att;

    Dimension latDim = ncfile.findDimension("lat");
    assert null != latDim;
    assert latDim.getShortName().equals("lat");
    assert latDim.getLength() == 3;
    assert !latDim.isUnlimited();

    Dimension timeDim = ncfile.findDimension("time");
    assert null != timeDim;
    assert timeDim.getShortName().equals("time");
    assert timeDim.getLength() == 2;
    assert timeDim.isUnlimited();
  }

  @Test
  public void testReadCoordvar() throws IOException {
    Variable lat = ncfile.findVariable("lat");
    assert null != lat;
    assert lat.getShortName().equals("lat");
    assert lat.getRank() == 1;
    assert lat.getSize() == 3;
    assert lat.getShape()[0] == 3;
    assert lat.getDataType() == DataType.FLOAT;

    assert !lat.isUnlimited();

    assert lat.getDimension(0) == ncfile.findDimension("lat");

    Attribute att = lat.findAttribute("units");
    assert null != att;
    assert !att.isArray();
    assert att.isString();
    assert att.getDataType() == DataType.STRING;
    assert att.getStringValue().equals("degrees_north");
    assert att.getNumericValue() == null;
    assert att.getNumericValue(3) == null;

    Array data = lat.read();
    assert data.getRank() == 1;
    assert data.getSize() == 3;
    assert data.getShape()[0] == 3;
    assert data.getElementType() == float.class;

    IndexIterator dataI = data.getIndexIterator();
    Assert2.assertNearlyEquals(dataI.getDoubleNext(), 41.0);
    Assert2.assertNearlyEquals(dataI.getDoubleNext(), 40.0);
    Assert2.assertNearlyEquals(dataI.getDoubleNext(), 39.0);
  }

  @Test
  public void testReadData() throws IOException {
    Variable v = ncfile.findVariable("rh");
    assert null != v;
    assert v.getShortName().equals("rh");
    assert v.getRank() == 3;
    assert v.getSize() == 24;
    assert v.getShape()[0] == 2;
    assert v.getShape()[1] == 3;
    assert v.getShape()[2] == 4;
    assert v.getDataType() == DataType.INT;

    assert !v.isCoordinateVariable();
    assert v.isUnlimited();

    assert v.getDimension(0) == ncfile.findDimension("time");
    assert v.getDimension(1) == ncfile.findDimension("lat");
    assert v.getDimension(2) == ncfile.findDimension("lon");

    Array data = v.read();
    assert data.getRank() == 3;
    assert data.getSize() == 24;
    assert data.getShape()[0] == 2;
    assert data.getShape()[1] == 3;
    assert data.getShape()[2] == 4;
    assert data.getElementType() == int.class;

    IndexIterator dataI = data.getIndexIterator();
    assert dataI.getIntNext() == 1;
    assert dataI.getIntNext() == 2;
    assert dataI.getIntNext() == 3;
    assert dataI.getIntNext() == 4;
    assert dataI.getIntNext() == 5;
  }

  @Test
  public void testReadSlice() throws IOException, InvalidRangeException {
    Variable v = ncfile.findVariable("rh");
    int[] origin = new int[3];
    int[] shape = {2, 3, 1};

    Array data = v.read(origin, shape);
    assert data.getRank() == 3;
    assert data.getSize() == 6;
    assert data.getShape()[0] == 2;
    assert data.getShape()[1] == 3;
    assert data.getShape()[2] == 1;
    assert data.getElementType() == int.class;

    IndexIterator dataI = data.getIndexIterator();
    assert dataI.getIntNext() == 1;
    assert dataI.getIntNext() == 5;
    assert dataI.getIntNext() == 9;
    assert dataI.getIntNext() == 21;
    assert dataI.getIntNext() == 25;
    assert dataI.getIntNext() == 29;
  }

  @Test
  public void testReadSlice2() throws IOException, InvalidRangeException {
    Variable v = ncfile.findVariable("rh");
    int[] origin = new int[3];
    int[] shape = {2, 1, 3};

    Array data = v.read(origin, shape).reduce();
    assert data.getRank() == 2;
    assert data.getSize() == 6;
    assert data.getShape()[0] == 2;
    assert data.getShape()[1] == 3;
    assert data.getElementType() == int.class;

    IndexIterator dataI = data.getIndexIterator();
    assert dataI.getIntNext() == 1;
    assert dataI.getIntNext() == 2;
    assert dataI.getIntNext() == 3;
    assert dataI.getIntNext() == 21;
    assert dataI.getIntNext() == 22;
    assert dataI.getIntNext() == 23;
  }

  @Test
  public void testReadData2() throws IOException {
    Variable v = ncfile.findVariable("Temperature");
    assert null == v;

    v = ncfile.findVariable("T");
    assert null != v;
    assert v.getShortName().equals("T");
    assert v.getRank() == 3;
    assert v.getSize() == 24;
    assert v.getShape()[0] == 2;
    assert v.getShape()[1] == 3;
    assert v.getShape()[2] == 4;
    assert v.getDataType() == DataType.DOUBLE;

    assert !v.isCoordinateVariable();
    assert v.isUnlimited();

    assert v.getDimension(0) == ncfile.findDimension("time");
    assert v.getDimension(1) == ncfile.findDimension("lat");
    assert v.getDimension(2) == ncfile.findDimension("lon");

    Attribute att = v.findAttribute("units");
    assert null != att;
    assert !att.isArray();
    assert att.isString();
    assert att.getDataType() == DataType.STRING;
    assert att.getStringValue().equals("degC");
    assert att.getNumericValue() == null;
    assert att.getNumericValue(3) == null;

    Array data = v.read();
    assert data.getRank() == 3;
    assert data.getSize() == 24;
    assert data.getShape()[0] == 2;
    assert data.getShape()[1] == 3;
    assert data.getShape()[2] == 4;
    assert data.getElementType() == double.class;

    IndexIterator dataI = data.getIndexIterator();
    Assert2.assertNearlyEquals(dataI.getDoubleNext(), 1.0);
    Assert2.assertNearlyEquals(dataI.getDoubleNext(), 2.0);
    Assert2.assertNearlyEquals(dataI.getDoubleNext(), 3.0);
    Assert2.assertNearlyEquals(dataI.getDoubleNext(), 4.0);
    Assert2.assertNearlyEquals(dataI.getDoubleNext(), 2.0);
  }
}
