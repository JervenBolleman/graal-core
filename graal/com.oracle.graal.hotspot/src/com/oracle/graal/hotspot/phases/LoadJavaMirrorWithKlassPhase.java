/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.graal.hotspot.phases;

import static com.oracle.graal.hotspot.replacements.HotSpotReplacementsUtil.CLASS_MIRROR_LOCATION;
import static com.oracle.graal.nodes.ConstantNode.getConstantNodes;
import static com.oracle.graal.nodes.NamedLocationIdentity.FINAL_LOCATION;

import com.oracle.graal.compiler.common.type.AbstractObjectStamp;
import com.oracle.graal.compiler.common.type.Stamp;
import com.oracle.graal.compiler.common.type.StampFactory;
import com.oracle.graal.compiler.common.type.TypeReference;
import com.oracle.graal.debug.GraalError;
import com.oracle.graal.hotspot.CompressEncoding;
import com.oracle.graal.hotspot.nodes.CompressionNode;
import com.oracle.graal.hotspot.nodes.type.KlassPointerStamp;
import com.oracle.graal.hotspot.nodes.type.NarrowOopStamp;
import com.oracle.graal.nodes.ConstantNode;
import com.oracle.graal.nodes.StructuredGraph;
import com.oracle.graal.nodes.ValueNode;
import com.oracle.graal.nodes.memory.FloatingReadNode;
import com.oracle.graal.nodes.memory.address.AddressNode;
import com.oracle.graal.nodes.memory.address.OffsetAddressNode;
import com.oracle.graal.phases.BasePhase;
import com.oracle.graal.phases.common.LoweringPhase;
import com.oracle.graal.phases.tiers.PhaseContext;

import jdk.vm.ci.hotspot.HotSpotObjectConstant;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaField;
import jdk.vm.ci.hotspot.HotSpotResolvedObjectType;
import jdk.vm.ci.hotspot.HotSpotResolvedPrimitiveType;
import jdk.vm.ci.meta.ConstantReflectionProvider;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaType;

/**
 * For AOT compilation we aren't allowed to use a {@link Class} reference ({@code javaMirror})
 * directly. Instead the {@link Class} reference should be obtained from the {@code Klass} object.
 * The reason for this is, that in Class Data Sharing (CDS) a {@code Klass} object is mapped to a
 * fixed address in memory, but the {@code javaMirror} is not (which lives in the Java heap).
 *
 * Lowering can introduce new {@link ConstantNode}s containing a {@link Class} reference, thus this
 * phase must be applied after {@link LoweringPhase}.
 *
 * @see AheadOfTimeVerificationPhase
 */
public class LoadJavaMirrorWithKlassPhase extends BasePhase<PhaseContext> {

    private final int classMirrorOffset;
    private final CompressEncoding oopEncoding;

    public LoadJavaMirrorWithKlassPhase(int classMirrorOffset, CompressEncoding oopEncoding) {
        this.classMirrorOffset = classMirrorOffset;
        this.oopEncoding = oopEncoding;
    }

    private ValueNode getClassConstantReplacement(StructuredGraph graph, PhaseContext context, JavaConstant constant) {
        if (constant instanceof HotSpotObjectConstant) {
            ConstantReflectionProvider constantReflection = context.getConstantReflection();
            ResolvedJavaType type = constantReflection.asJavaType(constant);
            if (type != null) {
                MetaAccessProvider metaAccess = context.getMetaAccess();
                Stamp stamp = StampFactory.objectNonNull(TypeReference.createExactTrusted(metaAccess.lookupJavaType(Class.class)));

                if (type instanceof HotSpotResolvedObjectType) {
                    ConstantNode klass = ConstantNode.forConstant(KlassPointerStamp.klassNonNull(), ((HotSpotResolvedObjectType) type).klass(), metaAccess, graph);
                    AddressNode address = graph.unique(new OffsetAddressNode(klass, ConstantNode.forLong(classMirrorOffset, graph)));
                    ValueNode read = graph.unique(new FloatingReadNode(address, CLASS_MIRROR_LOCATION, null, stamp));

                    if (((HotSpotObjectConstant) constant).isCompressed()) {
                        return CompressionNode.compress(read, oopEncoding);
                    } else {
                        return read;
                    }
                } else {
                    /*
                     * Primitive classes are more difficult since they don't have a corresponding
                     * Klass* so get them from Class.TYPE for the java box type.
                     */
                    HotSpotResolvedPrimitiveType primitive = (HotSpotResolvedPrimitiveType) type;
                    ResolvedJavaType boxingClass = metaAccess.lookupJavaType(primitive.getJavaKind().toBoxedJavaClass());
                    ConstantNode clazz = ConstantNode.forConstant(context.getConstantReflection().asJavaClass(boxingClass), metaAccess, graph);
                    HotSpotResolvedJavaField[] a = (HotSpotResolvedJavaField[]) boxingClass.getStaticFields();
                    HotSpotResolvedJavaField typeField = null;
                    for (HotSpotResolvedJavaField f : a) {
                        if (f.getName().equals("TYPE")) {
                            typeField = f;
                            break;
                        }
                    }
                    if (typeField == null) {
                        throw new GraalError("Can't find TYPE field in class");
                    }

                    if (oopEncoding != null) {
                        stamp = NarrowOopStamp.compressed((AbstractObjectStamp) stamp, oopEncoding);
                    }
                    AddressNode address = graph.unique(new OffsetAddressNode(clazz, ConstantNode.forLong(typeField.offset(), graph)));
                    ValueNode read = graph.unique(new FloatingReadNode(address, FINAL_LOCATION, null, stamp));

                    if (oopEncoding == null || ((HotSpotObjectConstant) constant).isCompressed()) {
                        return read;
                    } else {
                        return CompressionNode.uncompress(read, oopEncoding);
                    }
                }
            }
        }
        return null;
    }

    @Override
    protected void run(StructuredGraph graph, PhaseContext context) {
        for (ConstantNode node : getConstantNodes(graph)) {
            JavaConstant constant = node.asJavaConstant();
            ValueNode freadNode = getClassConstantReplacement(graph, context, constant);
            if (freadNode != null) {
                node.replace(graph, freadNode);
            }
        }
    }
}
