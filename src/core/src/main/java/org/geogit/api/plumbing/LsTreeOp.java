/* Copyright (c) 2013 OpenPlans. All rights reserved.
 * This code is licensed under the BSD New License, available at the root
 * application directory.
 */
package org.geogit.api.plumbing;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Iterator;
import java.util.List;

import org.geogit.api.AbstractGeoGitOp;
import org.geogit.api.Bounded;
import org.geogit.api.NodeRef;
import org.geogit.api.ObjectId;
import org.geogit.api.Ref;
import org.geogit.api.RevCommit;
import org.geogit.api.RevObject;
import org.geogit.api.RevObject.TYPE;
import org.geogit.api.RevTree;
import org.geogit.api.plumbing.diff.DepthTreeIterator;
import org.geogit.di.CanRunDuringConflict;
import org.geogit.storage.ObjectDatabase;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Supplier;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.inject.Inject;

/**
 * List the contents of a {@link RevTree tree} object as an Iterator&lt;{@link NodeRef}&gt;, using
 * the sepecified {@link Strategy strategy} to indicate what to return.
 * <p>
 * The tree to traverse is given as a {@link #setReference(String) ref spec}, as supported by
 * {@link RevParse#setRefSpec(String) RevParse} and must resolve to a tree object. If no ref spec is
 * specified, the root of the current working tree is assumed.
 */
@CanRunDuringConflict
public class LsTreeOp extends AbstractGeoGitOp<Iterator<NodeRef>> implements
        Supplier<Iterator<NodeRef>> {

    /**
     * Enumeration of the possible results of the {@link LsTreeOp} operation, indicating whether to
     * return the recursive contents of a tree or not, and whether to return feature and/or tree
     * child references.
     */
    public enum Strategy {
        /**
         * Default ls strategy, list the all direct child entries of a tree
         */
        CHILDREN,
        /**
         * List only the direct child entries of a tree that are of type FEATURE
         */
        FEATURES_ONLY,
        /**
         * List only the direct child entries of a tree that are of type TREE
         */
        TREES_ONLY,
        /**
         * Recursively list the contents of a tree in depth-first order, returning the tree ref when
         * a tree node is found followed by the contents of the subtree
         */
        DEPTHFIRST,
        /**
         * Recursively list the contents of a tree in depth-first order, but do not report TREE
         * entries, only FEATURE ones
         */
        DEPTHFIRST_ONLY_FEATURES,
        /**
         * Recursively list the contents of a tree in depth-first order, but do not report FEATURE
         * entries, only TREE ones
         */
        DEPTHFIRST_ONLY_TREES
    }

    private Strategy strategy;

    private String ref;

    private Predicate<Bounded> refBoundsFilter;

    @Inject
    public LsTreeOp() {
        this.strategy = Strategy.CHILDREN;
    }

    /**
     * @param path a path to list its content
     * @return {@code this}
     */
    public LsTreeOp setReference(final String ref) {
        this.ref = ref;
        return this;
    }

    public LsTreeOp setStrategy(final Strategy strategy) {
        Preconditions.checkNotNull(strategy);
        this.strategy = strategy;
        return this;
    }

    /**
     * @param refBoundsFilter
     * @return
     */
    public LsTreeOp setBoundsFilter(Predicate<Bounded> refBoundsFilter) {
        this.refBoundsFilter = refBoundsFilter;
        return this;
    }

    /**
     * @see java.util.concurrent.Callable#call()
     */
    public Iterator<NodeRef> call() {
        String ref = this.ref;

        if (ref == null) {
            ref = Ref.WORK_HEAD;
        }

        ObjectId metadataId = ObjectId.NULL;
        final String path = ref.lastIndexOf(':') != -1 ? ref.substring(ref.lastIndexOf(':') + 1)
                : "";
        if (!path.isEmpty()) {
            final String providedRefName = ref.lastIndexOf(':') != -1 ? ref.substring(0,
                    ref.lastIndexOf(':')) : null;
            if (providedRefName != null) {
                Optional<ObjectId> rootTreeId = command(ResolveTreeish.class).setTreeish(
                        providedRefName).call();
                if (rootTreeId.isPresent()) {
                    RevTree rootTree = command(RevObjectParse.class).setObjectId(rootTreeId.get())
                            .call(RevTree.class).get();

                    Optional<NodeRef> treeRef = command(FindTreeChild.class).setChildPath(path)
                            .setIndex(true).setParent(rootTree).call();
                    metadataId = treeRef.isPresent() ? treeRef.get().getMetadataId()
                            : ObjectId.NULL;
                }
            }
        }

        // is it just a ref name?
        Optional<Ref> reference = command(RefParse.class).setName(ref).call();
        if (reference.isPresent()) {
            if (reference.get().getObjectId().isNull()) {
                return Iterators.emptyIterator();
            }
        }
        Optional<RevObject> revObject = command(RevObjectParse.class).setRefSpec(ref).call(
                RevObject.class);

        Optional<NodeRef> treeRef = Optional.absent();

        if (!revObject.isPresent()) {
            if (Ref.WORK_HEAD.equals(ref)) { // we are requesting a listing of the whole working
                                             // tree but it is empty
                return Iterators.emptyIterator();
            }
            // let's try to see if it is a feature type or feature in the working tree
            NodeRef.checkValidPath(ref);

            treeRef = command(FindTreeChild.class).setParent(getWorkTree().getTree())
                    .setChildPath(ref).setIndex(true).call();

            Preconditions.checkArgument(treeRef.isPresent(), "Invalid reference: %s", ref);
            ObjectId treeId = treeRef.get().objectId();
            metadataId = treeRef.get().getMetadataId();
            revObject = command(RevObjectParse.class).setObjectId(treeId).call(RevObject.class);
        }

        checkArgument(revObject.isPresent(), "Invalid reference: %s", ref);

        final TYPE type = revObject.get().getType();
        switch (type) {
        case FEATURE:
            NodeRef nodeRef = treeRef.isPresent() ? treeRef.get() : null;
            List<NodeRef> nodeRefs = Lists.newArrayList();
            nodeRefs.add(nodeRef);
            // If show trees options is passed in show all trees that contain this feature
            if (this.strategy == Strategy.TREES_ONLY) {
                if (nodeRef != null) {
                    while (!nodeRef.getParentPath().isEmpty()) {
                        treeRef = command(FindTreeChild.class).setParent(getWorkTree().getTree())
                                .setChildPath(nodeRef.getParentPath()).setIndex(true).call();
                        nodeRef = treeRef.get();
                        nodeRefs.add(nodeRef);
                    }
                }
            }
            return nodeRefs.iterator();
        case COMMIT:
            RevCommit revCommit = (RevCommit) revObject.get();
            ObjectId treeId = revCommit.getTreeId();
            revObject = command(RevObjectParse.class).setObjectId(treeId).call(RevObject.class);
        case TREE:

            DepthTreeIterator.Strategy iterStrategy;

            switch (this.strategy) {
            case CHILDREN:
                iterStrategy = DepthTreeIterator.Strategy.CHILDREN;
                break;
            case FEATURES_ONLY:
                iterStrategy = DepthTreeIterator.Strategy.FEATURES_ONLY;
                break;
            case TREES_ONLY:
                iterStrategy = DepthTreeIterator.Strategy.TREES_ONLY;
                break;
            case DEPTHFIRST:
                iterStrategy = DepthTreeIterator.Strategy.RECURSIVE;
                break;
            case DEPTHFIRST_ONLY_FEATURES:
                iterStrategy = DepthTreeIterator.Strategy.RECURSIVE_FEATURES_ONLY;
                break;
            case DEPTHFIRST_ONLY_TREES:
                iterStrategy = DepthTreeIterator.Strategy.RECURSIVE_TREES_ONLY;
                break;
            default:
                throw new IllegalStateException("Unknown strategy: " + this.strategy);
            }

            RevTree tree = (RevTree) revObject.get();
            ObjectDatabase database = getIndex().getDatabase();
            DepthTreeIterator iter = new DepthTreeIterator(path, metadataId, tree, database,
                    iterStrategy);
            iter.setBoundsFilter(refBoundsFilter);
            return iter;
        default:
            throw new IllegalArgumentException(String.format("Invalid reference: %s", ref));
        }

    }

    /**
     * Implements {@link Supplier#get()} by deferring to {@link #call()}
     */
    @Override
    public Iterator<NodeRef> get() {
        return call();
    }
}