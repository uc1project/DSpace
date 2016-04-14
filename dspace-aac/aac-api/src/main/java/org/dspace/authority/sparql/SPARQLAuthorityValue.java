package org.dspace.authority.sparql;

import com.hp.hpl.jena.rdf.model.*;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.dspace.authority.AuthoritySource;
import org.dspace.authority.AuthorityTypes;
import org.dspace.authority.AuthorityValue;
import org.dspace.authority.AuthorityValueGenerator;
import org.dspace.authority.model.Concept;
import org.dspace.authority.model.Term;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.*;
import org.dspace.content.Collection;
import org.dspace.core.Context;
import org.dspace.utils.DSpace;

import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

/**
 * User: mini @ atmire . com
 * Date: 3/18/15
 * Time: 1:33 PM
 */
public class SPARQLAuthorityValue extends AuthorityValue {

    private static Logger log = Logger.getLogger(SPARQLAuthorityValue.class);

    private static final String TYPE = "sparql";

    public static final String SPARQLID = "meta_sparql_id";

    private String sparql_id;

    private boolean update; // used in setValues(Bio bio)

    private Model model;

    public String getSparql_id() {
        return sparql_id;
    }

    public void setSparql_id(String sparql_id) {
        this.sparql_id = sparql_id;
    }

    @Override
    public AuthorityValue newInstance(String field, String info) {
        if (StringUtils.isNotBlank(info)) {
            AuthorityTypes types = new DSpace().getServiceManager().getServiceByName("AuthorityTypes", AuthorityTypes.class);
            AuthoritySource source = types.getExternalSources().get(field);
            return source.queryAuthorityID(info);
        } else {
            SPARQLAuthorityValue sparqlAuthorityValue = new SPARQLAuthorityValue();
            sparqlAuthorityValue.setId(UUID.randomUUID().toString());
            sparqlAuthorityValue.updateLastModifiedDate();
            sparqlAuthorityValue.setCreationDate(new Date());
            return sparqlAuthorityValue;
        }
    }

    @Override
    public String getAuthorityType() {
        return TYPE;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        SPARQLAuthorityValue that = (SPARQLAuthorityValue) o;

        if (sparql_id != null ? !sparql_id.equals(that.sparql_id) : that.sparql_id != null) {
            return false;
        }

        return true;
    }

    @Override
    public SolrInputDocument getSolrInputDocument() {
        SolrInputDocument doc = super.getSolrInputDocument();
        if (StringUtils.isNotBlank(getSparql_id())) {
            doc.addField(SPARQLID, getSparql_id());
        }
        return doc;
    }

    @Override
    public int hashCode() {
        return sparql_id != null ? sparql_id.hashCode() : 0;
    }

    public boolean hasTheSameInformationAs(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.hasTheSameInformationAs(o)) {
            return false;
        }

        SPARQLAuthorityValue that = (SPARQLAuthorityValue) o;

        if (sparql_id != null ? !sparql_id.equals(that.sparql_id) : that.sparql_id != null) {
            return false;
        }

        return true;
    }


    @Override
    public Map<String, String> choiceSelectMap() {
        Map<String, String> map = super.choiceSelectMap();
        map.put(TYPE, getSparql_id());
        return map;
    }


    @Override
    public String generateString() {
        String generateString = AuthorityValueGenerator.GENERATE + getAuthorityType() + AuthorityValueGenerator.SPLIT;
        if (StringUtils.isNotBlank(getSparql_id())) {
            generateString += getSparql_id();
        }
        return generateString;
    }



    private MetadataField resolveField(String namespace, String prefix, String element)
    {
        Context context = null;

        try
        {
            context = new Context();
            context.turnOffAuthorisationSystem();

            MetadataSchema schema = MetadataSchema.findByNamespace(context, namespace);

            if(schema == null)
            {
                schema = new MetadataSchema(namespace,prefix);
                schema.create(context);
                context.commit();
            }

            MetadataField field = null;

            if(schema != null)
            {
                field = MetadataField.findByElement(context,schema.getSchemaID(),element,null);

                if(field == null)
                {
                    field = new MetadataField(schema,element,null,"autogenerated by authority control");
                    field.create(context);
                    context.commit();
                }
            }

            return field;

        } catch (Exception e) {
            log.error(e);
        } finally {
            if(context != null)
            {
                context.restoreAuthSystemState();
                context.abort();
            }
        }

        return null;
    }

    public void updateConceptFromAuthorityValue(Concept concept) throws SQLException,AuthorizeException {

        if(model != null)
        {
            StmtIterator iter =  model.listStatements();

            while(iter.hasNext())
            {
                Statement s = iter.nextStatement();
                Property property = s.getPredicate();
                RDFNode object = s.getObject();

                MetadataField field = resolveField(
                        property.getNameSpace(),
                        model.getNsURIPrefix(property.getNameSpace()),
                        property.getLocalName());

                if(field != null) {
                    concept.addMetadata(
                            model.getNsURIPrefix(property.getNameSpace()),
                            field.getElement(),
                            field.getQualifier(),
                            null,
                            object.toString());
                }
            }
        }

        for(String name : getNameVariants())
        {
            //add alternate terms
            Term term = concept.createTerm(name, Term.alternate_term);
            term.update();
        }
    }

    public void updateConceptFromAuthorityValue(Context context, Concept concept) throws SQLException,AuthorizeException {
        // First, update the concept from our authority value
        updateConceptFromAuthorityValue(concept);

        // For each metadata field,
        for (Metadatum metadata : concept.getMetadata()) {
            MetadataSchema schema = null;
            // If the schema is not found, make a new one.
            if ((schema = MetadataSchema.find(context, metadata.schema)) == null) {
                try {
                    schema = new MetadataSchema(model.getNsPrefixURI(metadata.schema), metadata.schema);
                    context.turnOffAuthorisationSystem();
                    schema.create(context);
                } catch (SQLException e) {
                    log.error(e.getMessage(),e);
                    throw e;
                } catch (NonUniqueMetadataException me) {
                    // This should never happen since we checked for this schema's existence
                    log.error(me.getMessage(), me);
                }
                finally
                {
                    context.restoreAuthSystemState();
                }
            }
            // If the field is not found, make a new one.
            if (MetadataField.findByElement(context, schema.getSchemaID(), metadata.element, metadata.qualifier) == null) {
                try {
                    context.turnOffAuthorisationSystem();
                    MetadataField field = new MetadataField(schema,metadata.element,metadata.qualifier,"Auto-Created by Authority Control");
                    field.create(context);
                } catch (SQLException e) {
                    log.error(e.getMessage(),e);
                    throw e;
                } catch (Exception me) {
                    // This should never happen since we checked for this field's existence
                    log.error(me.getMessage(),me);
                }
                finally {
                    context.restoreAuthSystemState();
                }
            }
        }
        concept.update();
    }

    public void setModel(Model model) {
        this.model = model;
    }

    public Model getModel() {
        return model;
    }

    @Override
    public void setValues(Concept concept) throws SQLException {
        super.setValues(concept);
        if(concept.getMetadata("dcterms","identifier",null, Item.ANY)!=null){
            this.sparql_id = concept.getMetadata("dcterms","identifier",null, Item.ANY)[0].value;
        }
    }

    @Override
    public void setValues(SolrDocument document) {
        super.setValues(document);
        this.sparql_id = String.valueOf(document.getFieldValue("sparql_id"));
    }
}