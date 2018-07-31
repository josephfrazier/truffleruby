/*
 * Copyright (c) 2015, 2017 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.constants;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.profiles.ConditionProfile;

import org.truffleruby.Layouts;
import org.truffleruby.core.module.ModuleFields;
import org.truffleruby.core.module.ModuleOperations;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.LexicalScope;
import org.truffleruby.language.RubyConstant;
import org.truffleruby.language.RubyNode;
import org.truffleruby.language.dispatch.CallDispatchHeadNode;
import org.truffleruby.language.loader.FeatureLoader;

@NodeChildren({ @NodeChild("lexicalScope"), @NodeChild("module"), @NodeChild("name"), @NodeChild("constant"), @NodeChild("lookupConstantNode") })
public abstract class GetConstantNode extends RubyNode {

    private final boolean callConstMissing;

    @Child private CallDispatchHeadNode constMissingNode;

    public static GetConstantNode create() {
        return create(true);
    }

    public static GetConstantNode create(boolean callConstMissing) {
        return GetConstantNodeGen.create(callConstMissing, null, null, null, null, null);
    }

    public Object lookupAndResolveConstant(LexicalScope lexicalScope, DynamicObject module, String name, LookupConstantInterface lookupConstantNode) {
        final RubyConstant constant = lookupConstantNode.lookupConstant(lexicalScope, module, name);
        return executeGetConstant(lexicalScope, module, name, constant, lookupConstantNode);
    }

    protected abstract Object executeGetConstant(LexicalScope lexicalScope, DynamicObject module, String name, RubyConstant constant, LookupConstantInterface lookupConstantNode);

    public GetConstantNode(boolean callConstMissing) {
        this.callConstMissing = callConstMissing;
    }

    @Specialization(guards = { "constant != null", "constant.hasValue()" })
    protected Object getConstant(LexicalScope lexicalScope, DynamicObject module, String name, RubyConstant constant, LookupConstantInterface lookupConstantNode) {
        return constant.getValue();
    }

    @TruffleBoundary
    @Specialization(guards = { "autoloadConstant != null", "autoloadConstant.isAutoload()" })
    protected Object autoloadConstant(LexicalScope lexicalScope, DynamicObject module, String name, RubyConstant autoloadConstant, LookupConstantInterface lookupConstantNode,
            @Cached("createOnSelf()") CallDispatchHeadNode callRequireNode) {

        final DynamicObject feature = autoloadConstant.getAutoloadPath();
        final DynamicObject autoloadConstantModule = autoloadConstant.getDeclaringModule();
        final ModuleFields fields = Layouts.MODULE.getFields(autoloadConstantModule);

        if (autoloadConstant.isAutoloadingThread()) {
            // Pretend the constant does not exist while it is autoloading
            return executeGetConstant(lexicalScope, module, name, null, lookupConstantNode);
        }

        final FeatureLoader featureLoader = getContext().getFeatureLoader();
        final String expandedPath = featureLoader.findFeature(StringOperations.getString(feature));
        if (expandedPath != null && featureLoader.getFileLocks().isCurrentThreadHoldingLock(expandedPath)) {
            // We found an autoload constant while we are already require-ing the autoload file,
            // consider it missing to avoid circular require warnings and calling #require twice.
            // For instance, autoload :RbConfig, "rbconfig"; require "rbconfig" causes this.
            return executeGetConstant(lexicalScope, module, name, null, lookupConstantNode);
        }

        autoloadConstant.startAutoLoad();
        try {

            // We need to notify cached lookup that we are autoloading the constant, as constant
            // lookup changes based on whether an autoload constant is loading or not (constant
            // lookup ignores being-autoloaded constants).
            fields.newConstantsVersion();

            callRequireNode.call(null, coreLibrary().getMainObject(), "require", feature);

            RubyConstant resolvedConstant = lookupConstantNode.lookupConstant(lexicalScope, module, name);

            // check if the constant was set in the ancestors of autoloadConstantModule
            if (resolvedConstant != null &&
                    (ModuleOperations.inAncestorsOf(resolvedConstant.getDeclaringModule(), autoloadConstantModule) ||
                            resolvedConstant.getDeclaringModule() == coreLibrary().getObjectClass())) {
                // all is good, just return that constant
            } else {
                // If the autoload constant was not set in the ancestors, undefine the constant
                fields.undefineConstantIfStillAutoload(autoloadConstant, name);

                // redo lookup, to consider the undefined constant
                resolvedConstant = lookupConstantNode.lookupConstant(lexicalScope, module, name);
            }

            return executeGetConstant(lexicalScope, module, name, resolvedConstant, lookupConstantNode);

        } finally {
            autoloadConstant.stopAutoLoad();
        }

    }

    @Specialization(
            guards = { "isNullOrUndefined(constant)", "guardName(name, cachedName, sameNameProfile)" },
            limit = "getCacheLimit()")
    protected Object missingConstantCached(LexicalScope lexicalScope, DynamicObject module, String name, Object constant, LookupConstantInterface lookupConstantNode,
            @Cached("name") String cachedName,
            @Cached("getSymbol(name)") DynamicObject symbolName,
            @Cached("createBinaryProfile()") ConditionProfile sameNameProfile) {
        if (callConstMissing) {
            return doMissingConstant(module, name, symbolName);
        } else {
            return null;
        }
    }

    @Specialization(guards = "isNullOrUndefined(constant)")
    protected Object missingConstantUncached(LexicalScope lexicalScope, DynamicObject module, String name, Object constant, LookupConstantInterface lookupConstantNode) {
        if (callConstMissing) {
            return doMissingConstant(module, name, getSymbol(name));
        } else {
            return null;
        }
    }

    private Object doMissingConstant(DynamicObject module, String name, DynamicObject symbolName) {
        if (constMissingNode == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            constMissingNode = insert(CallDispatchHeadNode.createOnSelf());
        }

        return constMissingNode.call(null, module, "const_missing", symbolName);
    }

    protected boolean isNullOrUndefined(Object constant) {
        return constant == null || ((RubyConstant) constant).isUndefined();
    }

    protected boolean guardName(String name, String cachedName, ConditionProfile sameNameProfile) {
        // This is likely as for literal constant lookup the name does not change and Symbols
        // always return the same String.
        if (sameNameProfile.profile(name == cachedName)) {
            return true;
        } else {
            return name.equals(cachedName);
        }
    }

    protected int getCacheLimit() {
        return getContext().getOptions().CONSTANT_CACHE;
    }

}
