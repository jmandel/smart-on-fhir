package fhir.searchParam

import org.hl7.fhir.instance.model.Resource
import org.hl7.fhir.instance.model.Conformance.SearchParamType
import org.w3c.dom.Node
import org.w3c.dom.NodeList

import com.mongodb.BasicDBObject;
import fhir.ResourceIndexTerm
import fhir.ResourceIndexToken


/**
 * @author jmandel
 *  Per FHIR spec, token-type search params can search through
 *    - text, displayname, code and code/codesystem (for codes)
 *    - label, system and key (for identifier)
 */
public class TokenSearchParamHandler extends SearchParamHandler {
/*	 :text (the match does a partial searches on
 *	          - the text portion of a CodeableConcept or
 *	          -  the display portion of a Coding)
 *	 :code (a match on code and system of
 *	          - the coding/codeable concept)
 *	 :anyns matches all codes irrespective of the namespace.
*/
	@Override
	protected String paramXpath() {
		return "//"+this.xpath;
	}

	
	void processMatchingXpaths(List<Node> tokens, List<IndexedValue> index){
		
		for (Node n : tokens) {

			// :text (the match does a partial searches on
			//          * the text portion of a CodeableConcept or
			//            the display portion of a Coding or
			//            the label portion of an Identifier)
			String text = query(".//f:label/@value | .//f:display/@value | .//f:text/@value", n).collect {
				it.nodeValue
			}.join(" ")

			// For CodeableConcept and Coding, list the code as "system/code"
			// For Identifier, list the code as "system/value"
			query(".//f:system", n).each { systemPart ->
				String system = queryString("./@value", systemPart);
				String code = queryString("../f:code/@value | ../f:value/@value", systemPart);
				index.add(value([
					namespace: system,	
					code: code,	
					text: text
				]))
			}

			// For plain 'ol Code elements, we'll at least pull out the value
			// (We won't try to determine the implicit system for now, since
			//  it's not available in instance data or profile.xml)
			query("./@value", n).each {Node codePart->
				index.add(value([
					code: codePart.nodeValue
				]))
			}
		}
	}

	@Override
	public ResourceIndexTerm createIndex(IndexedValue indexedValue, fhirId, fhirType) {
		def ret = new ResourceIndexToken()
		ret.search_param = indexedValue.handler.fieldName
		ret.fhir_id = fhirId
		ret.fhir_type = fhirType
		ret.token_code = indexedValue.dbFields.code
		ret.token_namespace  = indexedValue.dbFields.system
		ret.token_text = indexedValue.dbFields.text
		return ret
	}

	@Override
	BasicDBObject searchClause(Map searchedFor){
		// FHIR spec describes a slight difference between
		// no modifier and ":text" on a code --
		// (only :text should include display fields)
		// but we're treating them the same here
		if (searchedFor.modifier in [null, "code"]){
			return [(fieldName+':code'): searchedFor.value]
		}

		if (searchedFor.modifier == "text"){
			return [(fieldName+':text'): [$regex: searchedFor.value, $options: 'i']]
		}
			
		if (searchedFor.modifier == "anyns"){
			return [(fieldName+':code'): [$regex: '/'+searchedFor.value+'$']]
		}

		throw new RuntimeException("Unknown modifier: " + searchedFor)
	}
}
