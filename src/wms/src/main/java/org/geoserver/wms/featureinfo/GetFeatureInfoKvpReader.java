/* (c) 2014 - 2015 Open Source Geospatial Foundation - all rights reserved
 * (c) 2001 - 2014 OpenPlans
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wms.featureinfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.geoserver.catalog.LayerInfo;
import org.geoserver.ows.KvpRequestReader;
import org.geoserver.platform.ServiceException;
import org.geoserver.wms.GetFeatureInfoRequest;
import org.geoserver.wms.GetMapRequest;
import org.geoserver.wms.MapLayerInfo;
import org.geoserver.wms.WMS;
import org.geoserver.wms.WMSErrorCode;
import org.geoserver.wms.kvp.MapLayerInfoKvpParser;
import org.geoserver.wms.map.GetMapKvpRequestReader;
import org.geotools.util.Version;

/**
 * Builds a GetFeatureInfo request object given by a set of CGI parameters supplied in the
 * constructor.
 * <p>
 * Reads both WMS 1.1.1 and 1.3.0 GetFeatureInfo requests.
 * </p>
 * <p>
 * Request parameters:
 * </p>
 * 
 * @author Gabriel Roldan
 * @version $Id$
 */
public class GetFeatureInfoKvpReader extends KvpRequestReader {

    /** GetMap request reader used to parse the map context parameters needed. */
    private GetMapKvpRequestReader getMapReader;

    private WMS wms;

    public GetFeatureInfoKvpReader(WMS wms) {
        super(GetFeatureInfoRequest.class);
        getMapReader = new GetMapKvpRequestReader(wms);
        setWMS(wms);
    }

    public void setWMS(final WMS wms) {
        this.wms = wms;
    }

    public WMS getWMS() {
        return wms;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    @Override
    public Object read(Object req, Map kvp, Map rawKvp) throws Exception {
        GetFeatureInfoRequest request = (GetFeatureInfoRequest) super.read(req, kvp, rawKvp);
        request.setRawKvp(rawKvp);

        GetMapRequest getMapPart = new GetMapRequest();
        try {
            getMapPart = getMapReader.read(getMapPart, kvp, rawKvp);
        } catch (ServiceException se) {
            throw se;
        } catch (Exception e) {
            throw new ServiceException(e);
        }

        request.setGetMapRequest(getMapPart);
        
        List<MapLayerInfo> getMapLayers = getMapPart.getLayers();
        
        if ((getMapPart.getSldBody() != null || getMapPart.getSld() != null)
                && (rawKvp.get("QUERY_LAYERS") == null || "".equals(rawKvp.get("QUERY_LAYERS")))) {
            // in this case we assume all layers in SLD body are to be queried (GS own extension)(
            request.setQueryLayers(getMapLayers);
        } else {
            request.setQueryLayers(new MapLayerInfoKvpParser("QUERY_LAYERS", wms).parse((String) rawKvp
                    .get("QUERY_LAYERS")));
        }
        
        if (request.getQueryLayers().isEmpty()) {
            throw new ServiceException("No QUERY_LAYERS has been requested, or no "
                    + "queriable layer in the request anyways");
        }
        
        if(kvp.containsKey("propertyName")) {
            List<List<String>> propertyNames = (List<List<String>>) kvp.get("propertyName");
            if(propertyNames.size() == 1 && request.getQueryLayers().size() > 1) {
                // assume we asked the same list for all layers
                while(propertyNames.size() < request.getQueryLayers().size()) {
                    propertyNames.add(propertyNames.get(0));
                }
            }
            if(propertyNames.size() != request.getQueryLayers().size()) {
                throw new ServiceException("Mismatch between the property name set count " 
                        + propertyNames.size() + " and the query layers count " + request.getQueryLayers().size(),
                        "InvalidParameter", "propertyName");
            }
            request.setPropertyNames(propertyNames);
        }

        // make sure they are a subset of layers
        List<MapLayerInfo> queryLayers = new ArrayList<MapLayerInfo>(request.getQueryLayers());
        queryLayers.removeAll(getMapLayers);
        if (queryLayers.size() > 0) {
            // we've already expanded base layers so let's avoid list the names, they are not
            // the original ones anymore
            throw new ServiceException("QUERY_LAYERS contains layers not cited in LAYERS. "
                    + "It should be a proper subset of those instead");
        }

        // remove no-queryable layers
        for (MapLayerInfo l : request.getQueryLayers().toArray(new MapLayerInfo[0])) {
            LayerInfo layerInfo = l.getLayerInfo();
            if (!wms.isQueryable(layerInfo)) {
                int layerIndex = getMapLayers.indexOf(l);
                getMapLayers.remove(l);
                request.getQueryLayers().remove(l);
                if (layerIndex != -1) {
                    request.getGetMapRequest().getStyles().remove(layerIndex);
                }
            }
        }
        if (request.getQueryLayers().size() == 0) {
            throw new ServiceException("GetFeatureInfo request has not queryable layers",
                    WMSErrorCode.LAYER_NOT_QUERYABLE.get(request.getVersion()), "QUERY_LAYERS");
        }

        String format = (String) (kvp.containsKey("INFO_FORMAT") ? kvp.get("INFO_FORMAT") : null);

        if (format == null) {
            format = "text/plain";
        } else {
            List<String> infoFormats = wms.getAvailableFeatureInfoFormats();
            if (!infoFormats.contains(format)) {
                throw new ServiceException("Invalid format '" + format
                        + "', supported formats are " + infoFormats, "InvalidFormat", "info_format");
            }
            if (wms.getAllowedFeatureInfoFormats().contains(format)==false)
                throw wms.unallowedGetFeatureInfoFormatException(format);
        }

        request.setInfoFormat(format);

        request.setFeatureCount(1); // DJB: according to the WMS spec (7.3.3.7 FEATURE_COUNT) this
                                    // should be 1. also tested for by cite
        try {
            int maxFeatures = Integer.parseInt(String.valueOf(kvp.get("FEATURE_COUNT")));
            request.setFeatureCount(maxFeatures);
        } catch (NumberFormatException ex) {
            // do nothing, FEATURE_COUNT is optional
        }

        Version version = wms.negotiateVersion(request.getVersion()); 
        request.setVersion(version.toString());
        
        //JD: most wms 1.3 client implementations still use x/y rather than i/j, so we support those
        // too when i/j not specified when not running in strict cite compliance mode
        String colPixel, rowPixel;
        if(version.compareTo(WMS.VERSION_1_3_0) >= 0) {
            colPixel = "I";
            rowPixel = "J";
            
            if (!kvp.containsKey(colPixel) && !kvp.containsKey(rowPixel)) { 
                if (!wms.getServiceInfo().isCiteCompliant() && kvp.containsKey("X") 
                    && kvp.containsKey("Y")) {
                    colPixel = "X";
                    rowPixel = "Y"; 
                }
            }
        }
        else {
            colPixel = "X";
            rowPixel = "Y";
        }
        
        try {
            String colParam = String.valueOf(kvp.get(colPixel));
            String rowParam = String.valueOf(kvp.get(rowPixel));
            int x = Integer.parseInt(colParam);
            int y = Integer.parseInt(rowParam);
            
            //ensure x/y in dimension of image
            if (x < 0 || x > getMapPart.getWidth() || y < 0 || y > getMapPart.getHeight()) {
                throw new ServiceException(
                    String.format("%d, %d not in dimensions of image: %d, %d", x, y, 
                        getMapPart.getWidth(), getMapPart.getHeight()), "InvalidPoint");
            }
            request.setXPixel(x);
            request.setYPixel(y);
        } catch (NumberFormatException ex) {
            String msg = colPixel + " and " + rowPixel + " incorrectly specified";
            throw new ServiceException(msg, "InvalidPoint");
        }

        return request;
    }
}
