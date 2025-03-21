/**
 * Copyright (c) 2017-2018, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */

package com.powsybl.cgmes.model;

import com.powsybl.commons.datasource.ReadOnlyDataSource;
import com.powsybl.commons.report.ReportNode;
import com.powsybl.commons.report.TypedValue;
import com.powsybl.triplestore.api.PropertyBag;
import com.powsybl.triplestore.api.PropertyBags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Luma Zamarreño {@literal <zamarrenolm at aia.es>}
 */
public abstract class AbstractCgmesModel implements CgmesModel {

    protected AbstractCgmesModel() {
        // FIXME(Luma) we must remove properties from here. They are not used!
        this.properties = new Properties();
    }

    @Override
    public Properties getProperties() {
        return this.properties;
    }

    @Override
    public Map<String, PropertyBags> groupedTransformerEnds() {
        if (cachedGroupedTransformerEnds == null) {
            cachedGroupedTransformerEnds = computeGroupedTransformerEnds();
        }
        return cachedGroupedTransformerEnds;
    }

    @Override
    public Collection<CgmesTerminal> computedTerminals() {
        if (cachedTerminals == null) {
            cachedTerminals = computeTerminals();
        }
        return cachedTerminals.values();
    }

    @Override
    public CgmesTerminal terminal(String terminalId) {
        if (cachedTerminals == null) {
            cachedTerminals = computeTerminals();
        }
        return cachedTerminals.get(terminalId);
    }

    @Override
    public CgmesDcTerminal dcTerminal(String dcTerminalId) {
        if (cachedDcTerminals == null) {
            cachedDcTerminals = computeDcTerminals();
        }
        return cachedDcTerminals.get(dcTerminalId);
    }

    @Override
    public List<String> ratioTapChangerListForPowerTransformer(String powerTransformerId) {
        return powerTransformerRatioTapChanger.get(powerTransformerId) == null ? null : Arrays.asList(powerTransformerRatioTapChanger.get(powerTransformerId));
    }

    @Override
    public List<String> phaseTapChangerListForPowerTransformer(String powerTransformerId) {
        return powerTransformerPhaseTapChanger.get(powerTransformerId) == null ? null : Arrays.asList(powerTransformerPhaseTapChanger.get(powerTransformerId));
    }

    @Override
    public String substation(CgmesTerminal t, boolean nodeBreaker) {
        CgmesContainer c = container(t, nodeBreaker);
        if (c == null) {
            return null;
        }
        return c.substation();
    }

    @Override
    public String voltageLevel(CgmesTerminal t, boolean nodeBreaker) {
        CgmesContainer c = container(t, nodeBreaker);
        if (c == null) {
            return null;
        }
        return c.voltageLevel();
    }

    @Override
    public CgmesContainer container(String containerId) {
        if (cachedContainers == null) {
            cachedContainers = computeContainers();
        }
        if (cachedContainers.get(containerId) == null) { // container ID is substation
            String fixedContainerId = connectivityNodeContainers().stream()
                    .filter(p -> p.containsKey(SUBSTATION))
                    .filter(p -> p.getId(SUBSTATION).equals(containerId))
                    .findFirst()
                    .map(p -> p.getId("VoltageLevel"))
                    .orElseThrow(() -> new CgmesModelException(containerId + " should be a connectivity node container containing at least one voltage level"));
            LOG.warn("{} is a substation, not a voltage level, a line or a bay but contains nodes. " +
                    "The first CGMES voltage level found in this substation ({}}) is used instead.", containerId, fixedContainerId);
            cachedContainers.put(containerId, cachedContainers.get(fixedContainerId));
        }
        return cachedContainers.get(containerId);
    }

    @Override
    public double nominalVoltage(String baseVoltageId) {
        if (cachedBaseVoltages == null) {
            cachedBaseVoltages = new HashMap<>();
            baseVoltages()
                .forEach(bv -> cachedBaseVoltages.put(bv.getId("BaseVoltage"), bv.asDouble("nominalVoltage")));
        }
        return cachedBaseVoltages.getOrDefault(baseVoltageId, Double.NaN);
    }

    private CgmesContainer container(CgmesTerminal t, boolean nodeBreaker) {
        cacheNodes();
        String containerId = null;
        String nodeId = nodeBreaker && t.connectivityNode() != null ? t.connectivityNode() : t.topologicalNode();
        if (nodeId != null) {
            PropertyBag node = cachedNodesById.get(nodeId);
            if (node != null) {
                containerId = node.getId("ConnectivityNodeContainer");
            } else {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Missing node {} from terminal {}", nodeId, t.id());
                }
            }
        }

        return (containerId == null) ? null : container(containerId);
    }

    private Map<String, PropertyBags> computeGroupedTransformerEnds() {
        // Alternative implementation:
        // instead of sorting after building each list,
        // use a sorted collection when inserting
        String endNumber = "endNumber";
        Map<String, PropertyBags> gends = new HashMap<>();
        powerTransformerRatioTapChanger = new HashMap<>();
        powerTransformerPhaseTapChanger = new HashMap<>();
        transformerEnds()
            .forEach(end -> {
                String id = end.getId("PowerTransformer");
                PropertyBags ends = gends.computeIfAbsent(id, x -> new PropertyBags());
                ends.add(end);
                if (end.getId("PhaseTapChanger") != null) {
                    powerTransformerPhaseTapChanger.computeIfAbsent(id, s -> new String[3]);
                    powerTransformerPhaseTapChanger.get(id)[end.asInt(endNumber, 1) - 1] = end.getId("PhaseTapChanger");
                }
                if (end.getId("RatioTapChanger") != null) {
                    powerTransformerRatioTapChanger.computeIfAbsent(id, s -> new String[3]);
                    powerTransformerRatioTapChanger.get(id)[end.asInt(endNumber, 1) - 1] = end.getId("RatioTapChanger");
                }
            });
        gends.entrySet()
            .forEach(tends -> {
                PropertyBags tends1 = new PropertyBags(
                    tends.getValue().stream()
                        .sorted(Comparator
                            .comparing(WindingType::fromTransformerEnd)
                            .thenComparing(end -> end.asInt(endNumber, -1)))
                        .collect(Collectors.toList()));
                tends.setValue(tends1);
            });
        return gends;
    }

    protected void cacheNodes() {
        if (!cachedNodes) {
            cachedConnectivityNodes = connectivityNodes();
            cachedTopologicalNodes = topologicalNodes();
            cachedNodesById = new HashMap<>();
            cachedConnectivityNodes.forEach(cn -> cachedNodesById.put(cn.getId("ConnectivityNode"), cn));
            cachedTopologicalNodes.forEach(tn -> cachedNodesById.put(tn.getId("TopologicalNode"), tn));
            cachedNodes = true;
        }
    }

    private Map<String, CgmesTerminal> computeTerminals() {
        Map<String, CgmesTerminal> ts = new HashMap<>();
        terminals().forEach(t -> {
            CgmesTerminal td = new CgmesTerminal(t);
            if (ts.containsKey(td.id())) {
                return;
            }
            ts.put(td.id(), td);
        });
        return ts;
    }

    private Map<String, CgmesDcTerminal> computeDcTerminals() {
        Map<String, CgmesDcTerminal> ts = new HashMap<>();
        dcTerminals().forEach(t -> {
            CgmesDcTerminal td = new CgmesDcTerminal(t);
            if (ts.containsKey(td.id())) {
                return;
            }
            ts.put(td.id(), td);
        });
        return ts;
    }

    // TODO(Luma): better caches create an object "Cache" that is final ...
    // (avoid filling all places with if cached == null...)
    private Map<String, CgmesContainer> computeContainers() {
        Map<String, CgmesContainer> cs = new HashMap<>();
        connectivityNodeContainers().forEach(c -> {
            String id = c.getId("ConnectivityNodeContainer");
            String voltageLevel = c.getId("VoltageLevel");
            String substation = c.getId(SUBSTATION);
            cs.put(id, new CgmesContainer(voltageLevel, substation));
        });
        return cs;
    }

    // read/write

    @Override
    public void setBasename(String baseName) {
        this.baseName = Objects.requireNonNull(baseName);
    }

    @Override
    public String getBasename() {
        return baseName;
    }

    @Override
    public void read(ReadOnlyDataSource mainDataSource, ReadOnlyDataSource alternativeDataSourceForBoundary, ReportNode reportNode) {
        setBasename(CgmesModel.baseName(mainDataSource));
        read(mainDataSource, reportNode);
        if (!hasBoundary() && alternativeDataSourceForBoundary != null) {
            read(alternativeDataSourceForBoundary, reportNode);
        }
    }

    @Override
    public void read(ReadOnlyDataSource ds, ReportNode reportNode) {
        Objects.requireNonNull(reportNode);
        invalidateCaches();
        CgmesOnDataSource cds = new CgmesOnDataSource(ds);
        for (String name : cds.names()) {
            LOG.info("Reading [{}]", name);
            reportNode.newReportNode()
                    .withMessageTemplate("CGMESFileRead", "Instance file ${instanceFile}")
                    .withTypedValue("instanceFile", name, TypedValue.FILENAME)
                    .withSeverity(TypedValue.INFO_SEVERITY)
                    .add();
            try (InputStream is = cds.dataSource().newInputStream(name)) {
                read(is, baseName, name, reportNode);
            } catch (IOException e) {
                String msg = String.format("Reading [%s]", name);
                LOG.warn(msg);
                throw new CgmesModelException(msg, e);
            }
        }
    }

    protected void invalidateCaches() {
        cachedGroupedTransformerEnds = null;
        powerTransformerRatioTapChanger = null;
        powerTransformerPhaseTapChanger = null;
        cachedTerminals = null;
        cachedContainers = null;
        cachedBaseVoltages = null;
        cachedNodes = false;
        cachedConnectivityNodes = null;
        cachedTopologicalNodes = null;
        cachedNodesById = null;
        cachedDcTerminals = null;
    }

    private final Properties properties;
    private String baseName;

    // Caches
    private Map<String, PropertyBags> cachedGroupedTransformerEnds;
    private Map<String, CgmesTerminal> cachedTerminals;
    private Map<String, CgmesContainer> cachedContainers;
    private Map<String, Double> cachedBaseVoltages;
    protected boolean cachedNodes = false;
    protected PropertyBags cachedConnectivityNodes;
    protected PropertyBags cachedTopologicalNodes;
    private Map<String, PropertyBag> cachedNodesById;
    // equipmentId, sequenceNumber, terminalId
    private Map<String, String[]> powerTransformerRatioTapChanger;
    private Map<String, String[]> powerTransformerPhaseTapChanger;
    private Map<String, CgmesDcTerminal> cachedDcTerminals;

    private static final Logger LOG = LoggerFactory.getLogger(AbstractCgmesModel.class);
    private static final String SUBSTATION = "Substation";
}
