package org.strategoxt.imp.runtime.stratego;

import lpg.runtime.ILexStream;

import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.library.AbstractPrimitive;
import org.spoofax.interpreter.stratego.Strategy;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.strategoxt.imp.runtime.stratego.adapter.IWrappedAstNode;

/**
 * Extracts all comment lines directly in front of the current node
 * @author Maartje de Jonge
 */
public class OriginLeftCommentLinesPrimitive extends AbstractPrimitive {
	
	private static final String NAME = "SSL_EXT_origin_left_comment";
	

	public OriginLeftCommentLinesPrimitive() {
		super(NAME, 0, 1);
	}
	
	@Override
	public final boolean call(IContext env, Strategy[] svars, IStrategoTerm[] tvars) {
		
		if (tvars[0] instanceof IWrappedAstNode) {
			IStrategoTerm result = call(env, (IWrappedAstNode) tvars[0]);
			if (result != null) {
				env.setCurrent(result);
				return true;
			}
		}
		return false;
	}
	
	private IStrategoTerm call(IContext env, IWrappedAstNode node) {		
		int commentStart=TextPositions.getStartPosCommentBefore(node.getNode());
		int commentEnd=TextPositions.getEndPosCommentBefore(node.getNode());
		if(commentStart>0){
			ILexStream lexStream=node.getNode().getLeftIToken().getILexStream();
			String commentText=lexStream.toString(commentStart, commentEnd-1);
			return env.getFactory().makeTuple(
					env.getFactory().makeInt(commentStart),
					env.getFactory().makeInt(commentEnd),
					env.getFactory().makeString(commentText)
			);
		}
		return null;
	}
}
