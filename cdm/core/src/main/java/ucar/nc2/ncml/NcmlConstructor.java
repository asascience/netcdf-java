/*
 * Copyright (c) 1998-2018 John Caron and University Corporation for Atmospheric Research/Unidata
 * See LICENSE for license information.
 */

package ucar.nc2.ncml;

import java.nio.charset.StandardCharsets;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.jdom2.output.XMLOutputter;
import thredds.client.catalog.Catalog;
import ucar.ma2.*;
import ucar.nc2.*;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Populate a NetcdfFile directly from NcML, can be used by IOSPs.
 * All ncml elements are new, not modified.
 *
 * @deprecated do not use
 */
@Deprecated
public class NcmlConstructor {
  // static private final boolean validate = false;
  private static final boolean debugConstruct = false;
  private static final boolean showParsedXML = false;

  private Formatter errlog = new Formatter();

  public Formatter getErrlog() {
    return errlog;
  }

  /**
   *
   * @param resourceLocation eg "resources/nj22/iosp/ghcnm.ncml"
   * @param target populate this file
   * @return true if success
   * @throws IOException on error
   */
  public boolean populateFromResource(String resourceLocation, NetcdfFile target) throws IOException {
    ClassLoader cl = this.getClass().getClassLoader();
    InputStream is = cl.getResourceAsStream(resourceLocation);
    if (is == null)
      throw new FileNotFoundException(resourceLocation);
    return populate(is, target);
  }

  public boolean populate(String ncml, NetcdfFile target) throws IOException {
    return populate(new ByteArrayInputStream(ncml.getBytes(StandardCharsets.UTF_8)), target);
  }

  public boolean populate(InputStream ncml, NetcdfFile target) throws IOException {
    org.jdom2.Document doc;
    try {
      SAXBuilder builder = new SAXBuilder();
      doc = builder.build(ncml);
    } catch (JDOMException e) {
      throw new IOException(e.getMessage());
    }
    if (showParsedXML) {
      XMLOutputter xmlOut = new XMLOutputter();
      System.out.println("*** NetcdfDataset/showParsedXML = \n" + xmlOut.outputString(doc) + "\n*******");
    }

    Element netcdfElem = doc.getRootElement();
    readGroup(target, target.getRootGroup(), netcdfElem);
    return errlog.toString().isEmpty();
  }

  private void readGroup(NetcdfFile ncfile, Group parent, Element groupElem) {

    String name = groupElem.getAttributeValue("name");

    Group g;
    if (parent == ncfile.getRootGroup()) { // special handling
      g = parent;

    } else {
      if (name == null) {
        errlog.format("NcML Group name is required (%s)%n", groupElem);
        return;
      }
      g = new Group(ncfile, parent, name);
      parent.addGroup(g);
    }

    // look for attributes
    java.util.List<Element> attList = groupElem.getChildren("attribute", Catalog.ncmlNS);
    for (Element attElem : attList) {
      readAtt(g, attElem);
    }

    // look for dimensions
    java.util.List<Element> dimList = groupElem.getChildren("dimension", Catalog.ncmlNS);
    for (Element dimElem : dimList) {
      readDim(g, dimElem);
    }

    // look for variables
    java.util.List<Element> varList = groupElem.getChildren("variable", Catalog.ncmlNS);
    for (Element varElem : varList) {
      readVariable(ncfile, g, null, varElem);
    }

    // LOOK for typedef enums

    // look for nested groups
    java.util.List<Element> groupList = groupElem.getChildren("group", Catalog.ncmlNS);
    for (Element gElem : groupList) {
      readGroup(ncfile, g, gElem);
    }
  }

  /**
   * Read a NcML variable element, and nested elements, when it creates a new Variable.
   *
   * @param ncfile target dataset
   * @param g parent Group
   * @param parentS parent Structure
   * @param varElem ncml variable element
   * @return return new Variable
   */
  private Variable readVariable(NetcdfFile ncfile, Group g, Structure parentS, Element varElem) {
    String name = varElem.getAttributeValue("name");
    if (name == null) {
      errlog.format("NcML Variable name is required (%s)%n", varElem);
      return null;
    }

    String type = varElem.getAttributeValue("type");
    if (type == null) {
      errlog.format("NcML variable (%s) must have type attribute", name);
      return null;
    }
    DataType dtype = DataType.getType(type);

    String shape = varElem.getAttributeValue("shape");
    if (shape == null)
      shape = ""; // deprecated, prefer explicit ""

    Variable v;

    if (dtype == DataType.STRUCTURE) {
      Structure s = new Structure(ncfile, g, parentS, name);
      s.setDimensions(shape);
      v = s;
      // look for nested variables
      java.util.List<Element> varList = varElem.getChildren("variable", Catalog.ncmlNS);
      for (Element vElem : varList) {
        readVariable(ncfile, g, s, vElem);
      }

    } else if (dtype == DataType.SEQUENCE) {
      Sequence s = new Sequence(ncfile, g, parentS, name);
      v = s;
      // look for nested variables
      java.util.List<Element> varList = varElem.getChildren("variable", Catalog.ncmlNS);
      for (Element vElem : varList) {
        readVariable(ncfile, g, s, vElem);
      }

    } else {
      v = new Variable(ncfile, g, parentS, name, dtype, shape);

      // deal with values
      Element valueElem = varElem.getChild("values", Catalog.ncmlNS);
      if (valueElem != null)
        readValues(v, varElem, valueElem);
      // otherwise has fill values.
    }

    // look for attributes
    java.util.List<Element> attList = varElem.getChildren("attribute", Catalog.ncmlNS);
    for (Element attElem : attList)
      readAtt(v, attElem);

    if (parentS != null)
      parentS.addMemberVariable(v);
    else
      g.addVariable(v);

    return v;
  }

  private void readValues(Variable v, Element varElem, Element valuesElem) {

    // check if values are specified by start / increment
    String startS = valuesElem.getAttributeValue("start");
    String incrS = valuesElem.getAttributeValue("increment");
    String nptsS = valuesElem.getAttributeValue("npts");
    int npts = (nptsS == null) ? (int) v.getSize() : Integer.parseInt(nptsS);

    // either start, increment are specified
    if ((startS != null) && (incrS != null)) {
      double start = Double.parseDouble(startS);
      double incr = Double.parseDouble(incrS);
      v.setValues(npts, start, incr);
      return;
    }

    // otherwise values are listed in text
    String values = varElem.getChildText("values", Catalog.ncmlNS);
    String sep = valuesElem.getAttributeValue("separator");
    if (sep == null)
      sep = " ";

    if (v.getDataType() == DataType.CHAR) {
      int nhave = values.length();
      int nwant = (int) v.getSize();
      char[] data = new char[nwant];
      int min = Math.min(nhave, nwant);
      for (int i = 0; i < min; i++) {
        data[i] = values.charAt(i);
      }
      Array dataArray = Array.factory(DataType.CHAR, v.getShape(), data);
      v.setCachedData(dataArray, true);

    } else {
      // or a list of values
      List<String> valList = new ArrayList<>();
      StringTokenizer tokn = new StringTokenizer(values, sep);
      while (tokn.hasMoreTokens())
        valList.add(tokn.nextToken());
      v.setValues(valList);
    }
  }

  private void readAtt(Object parent, Element attElem) {
    String name = attElem.getAttributeValue("name");
    if (name == null) {
      errlog.format("NcML Attribute name is required (%s)%n", attElem);
      return;
    }

    try {
      ucar.ma2.Array values = NcMLReader.readAttributeValues(attElem);
      Attribute att = new ucar.nc2.Attribute(name, values);
      if (parent instanceof Group)
        ((Group) parent).addAttribute(att);
      else if (parent instanceof Variable)
        ((Variable) parent).addAttribute(att);
    } catch (RuntimeException e) {
      errlog.format("NcML new Attribute Exception: %s att=%s in=%s%n", e.getMessage(), name, parent);
    }
  }

  /**
   * Read an NcML dimension element.
   *
   * @param g put dimension into this group
   * @param dimElem ncml dimension element
   */
  private void readDim(Group g, Element dimElem) {
    String name = dimElem.getAttributeValue("name");
    if (name == null) {
      errlog.format("NcML Dimension name is required (%s)%n", dimElem);
      return;
    }

    String lengthS = dimElem.getAttributeValue("length");
    String isUnlimitedS = dimElem.getAttributeValue("isUnlimited");
    String isSharedS = dimElem.getAttributeValue("isShared");
    String isUnknownS = dimElem.getAttributeValue("isVariableLength");

    boolean isUnlimited = "true".equalsIgnoreCase(isUnlimitedS);
    boolean isUnknown = "true".equalsIgnoreCase(isUnknownS);
    boolean isShared = true;
    if ("false".equalsIgnoreCase(isSharedS))
      isShared = false;

    int len = Integer.parseInt(lengthS);
    if ("false".equalsIgnoreCase(isUnknownS))
      len = Dimension.VLEN.getLength();

    Dimension dim = new Dimension(name, len, isShared, isUnlimited, isUnknown);

    if (debugConstruct)
      System.out.println(" add new dim = " + dim);
    g.addDimension(dim);
  }

}
