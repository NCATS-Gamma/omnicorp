import os
import requests
from collections import defaultdict
import src.postprocess.btypes as btypes

#These are curies that tend to give a lot of false positives, or are generally
# too general to be of interest
# Genes are handled differently because the filtering happens at a per-annotation level, not an
# entity level.
garbage_curies = set(['MONDO:0019395',  #Hinman syndrome with synonym "HAS",
                      'MONDO:0012833',  #"CAN"
                      'MONDO:0000001',  #disease
                      'MONDO:0004967',  #"ALL"
                      'UBERON:0014899',  #"ALL",
                      'HP:0000001',
                      'UBERON:0006611',  #Has synonym test
                      'ENVO:00000026',  #well
                      'CHEBI:33232',  #application
                      'CHEBI:75958',  #solution
                      'ENVO:01000584',  #table
                      'ENVO:2000036',  #generates
                      'HP:0012824',  #severity
                      'HP:0012830',  #Position
                      'HP:0012834',  #Right
                      'HP:0012835',  #Left
                      'HP:0032320',  #Affected
                      'HP:0040279',  #Frequency
                      'HP:0040285',  #Excluded
                      'HP:0012832',  #Bilateral
                      'CHEBI:24433',  #Group
                      'CHEBI:52217',  #Pharmaceutical
                      'CHEBI:23888',  #Drug
                      'CHEBI:50906',  #Role
                      'HP:0011009',  #Acute
                      'HP:0012828',  #Severe
                      'HP:0025254',  #Ameliorated by
                      'HP:0032322',  #Healthy
                      'MONDO:0021137',  #not rare
                      'NCBITaxon:order',
                      'NCBITaxon:family',
                      'CHEBI:35225',  #buffer
                      'CHEBI:60004',  #mixture
                      'CHEBI:25367',  #molecule
                      'GO:0005623',  #cell
                      'FOODON:03420236',  #foodon
                      'ENVO:01000605',  #car
                      'FOODON:00003004',  #animal
                      'GO:0005488',  #binding
                      'HP:0012825',  #Mild
                      'HP:0030646',  #Peripheral
                      'HP:0040282',  #Frequent
                      'HP:0003674',  #Onset
                      'UBERON:0004529',  #anatomical projection
                      'HP:0011010',  #Chronic
                      'CHEBI:33893',  #reagent
                      'MONDO:0045042',  #localized
                      'FOODON:03430131',  #whole
                      'FOODON:03412846',  #bacteria as food
                      'HP:0025303',  #episodic
                      'HP:0003745',  #Sporadic
                      'HP:0003676',  #Progressive
                      'HP:0025275',  #Lateral
                      'HP:0020034',  #Diffuse
                      'HP:0031797',  #Clinical course
                      'HP:0012833',  #Unilateral
                      'CHEBI:75830',  #HOME
                      'CHEBI:33731',  #cluster
                      'NCBITaxon:kingdom',
                      'NCBITaxon:phylum',
                      'NCBITaxon:subphylum',
                      'NCBITaxon:class',
                      'NCBITaxon:subclass',
                      'NCBITaxon:order',
                      'NCBITaxon:superfamily',
                      'NCBITaxon:family',
                      'NCBITaxon:subfamily',
                      'NCBITaxon:genus',
                      'NCBITaxon:species',
                      'NCBITaxon:tribe',
                      'NCBITaxon:9605',  #Homo
                      'NCBITaxon:3846',  #Glycine (?)
                      'NCBITaxon:1',  #Root
                      'NCBITaxon:2',  #Bacteria
                      'MONDO:0021141',  #acquired
                      'ENVO:00000444',  #clearing
                      'ENVO:00000427',  #meander
                      'CHEBI:33250',  #atom
                      'ENVO:01000604',  #vehicle
                      'HP:0025297',  #Prolongued
                      'HP:0012840',  #Proximal
                      'ENVO:01000588',  #sofa
                      'CHEBI:3608',  #protein
                      'ENVO:00000480',  #peak
                      'HP:0012829',  #Profound
                      'HP:0025285',  #Aggravated by
                      'UPHENO:0001001',  #PHENOTYPE
                      'NCBITaxon:695168',  #AREAS
                      'NCBITaxon:12939',  #Anemia.  Who the &*(@&# names a fern anemia?
                      'NCBITaxon:1369087',  #data
                      'NCBITaxon:3493',  #fig
                      'CHEBI:27889',  #lead
                      'GO:0043336',  # has synonym REST
                      'GO:0032502',  #Development
                      'GO:0008150',  #biological process
                      'GO:0046903',  #secretion
                      'GO:0097194',  #execution phase of apoptosis (has apoptosis as synonym)
                      'GO:0010467',  #gene expression
                      ])


#We're not incorporating any of these at the moment
unused_prefixes = ['DDANAT', 'UO', 'SO', 'RO', 'EFO', 'PR', 'MF', 'OBI', 'VO',
                   'BFO', 'PATO', 'IAO', 'BSPO', 'IDO', 'BTO', 'http', 'TERMS',
                   'OGMS', 'FMA', 'MP', 'MPATH', 'NBO', 'MA', 'UBPROP', 'UBREL',
                   'CARO', 'PCO', 'OBA', 'CLO', 'EO', 'FBBT', 'CHMO', 'SIO', 'OMIT',
                   'CP', 'GAZ', 'ORPHANET', 'TO', 'OPL', 'ZFA', 'NCIT', 'ZEA', 'MOD',
                   'MGI', 'REO', 'OMIABIS', 'GENE', 'WBLS','DEPICTED','UPHENO','LOCUS']

class Normalizer():
    def __init__(self,indir,pmidcol=0,termcol=1):
        """Class for converting ontology / vocabulary terms from scigraph into Translator-compliant entities.
            Input terms are added to the normalizer using 'add'.  Once all terms are added, 'normalize_all' calls
            the Translator node-normalization service in batches (for efficiency).   Additionally, there are some
            special cases to handle identifiers that are not found in nodenorm, and to remove some identifiers that
            are often false positives."""
        self.url = 'https://nodenormalization-sri.renci.org/get_normalized_nodes'
        #self.curie_to_iri = {}
        self.iri_to_curie = {}
        #self.iri_to_label = {}
        self.curie_to_normalized = {}
        #self.curie_to_type = {}
        self.curie_to_label = {}
        self.normfile=(f"{indir}/normalization_map")
        if os.path.exists(self.normfile):
            self.load()
        else:
            self.create_map(indir,pmidcol,termcol)
    def load(self):
        with open(self.normfile,"r") as inf:
            h = inf.readline()
            #h='iri\tcurie\tnormalized_curie\n'
            for line in inf:
                x = line.strip('\n').split('\t')
                self.iri_to_curie[x[0]] = x[1]
                self.curie_to_normalized[x[1]] = x[2]
    def create_map(self,indir,pmidcol,termcol):
        rfiles = os.listdir(indir)
        # TODO: write out an actual iri->normcurie map.
        for rf in rfiles:
            if not rf.endswith("tsv"):
                continue
            with open(f'{indir}/{rf}', 'r') as inf:
                for line in inf:
                    x = line.strip().split('\t')
                    pmid = x[pmidcol]
                    term = x[termcol]
                    self.add(term)
        self.normalize_all()
        self.write(f'{indir}/normalization_map')
    def add(self,iri,label=None):
        """Add a new IRI and label to the normalizer"""
        useless_iri = set()
        if iri in self.iri_to_curie:
            return
        if iri in useless_iri:
            return
        newcurie = self._curify(iri)
        if newcurie is None:
            useless_iri.add(iri)
            return
        if '#' in newcurie:
            print ("how did I get here?")
            print(iri)
            exit()
        self.iri_to_curie[iri] = newcurie
        #self.iri_to_label[iri] = label
        self.curie_to_iri[newcurie] = iri
    def _curify(self,iri):
        """Convert IRI to curie"""
        #These don't convert nicely, junk them
        if iri.startswith('http://dbpedia.org/resource/') or \
           iri.startswith('http://purl.org/dc/elements/') or \
           iri.startswith('http://purl.org/dc/terms/') or \
           iri.startswith('http://xmlns.com/foaf/') or \
           iri.startswith('http://www.ensembl.org/') or \
           iri.startswith('http://flybase.org/') or \
           iri.startswith('http://dictybase.org/') or \
           iri.startswith('http://www.pombase.org/') or \
           iri.startswith('http://zfin.org/') or \
           iri.startswith('http://www.informatics.jax.org/') or \
           iri.startswith('http://www.yeastgenome.org/cgi-bin/') or \
           iri.startswith('https://www.wikidata.org/'):
           return None
        ns = iri.split('/')
        n = ns[-1]
        if n.startswith('gene_symbol_report'):
            return f"HGNC:{n.split('=')[-1]}"
        if ':' in n:
            return n
        x = n.split('_')
        c = None
        if len(x) == 2:
            x[0] = x[0].upper()
            if x[0] in unused_prefixes:
                return None
            c = ':'.join(x)
        if len(x) == 1:
            prefix = ns[-2].upper()
            if prefix in unused_prefixes:
                return None
            c = f'{prefix}:{ns[-1]}'
        if c is not None:
            if '#' in c:
                return None
            return c
        return None
    def normalize(self,iri):
        try:
            c = self.iri_to_curie[iri]
            n = self.curie_to_normalized[c]
            return n
        except:
            return None
    def normalize_all(self):
        """Call node normalization on batches of curies, and convert into their
        translator-preferred forms"""
        batchsize=1000
        unnormalized_curies = self.iri_to_curie.values()
        print(len(unnormalized_curies))
        print(len(unnormalized_curies)/batchsize)
        for i in range(0, len(unnormalized_curies), batchsize):
            batch = unnormalized_curies[i:i + batchsize]
            batchmap, typemap, labelmap = self._normalize_batch(batch)
            self.curie_to_normalized.update(batchmap)
            self.curie_to_label.update(labelmap)
        self._remove_obsolete_terms()
        self._remove_garbage()
        #It's possible here that we have an iri that got turned into a curie,
        # and we sent it to normalization, and it didn't normalize, and we end
        # up with no type labels.  Need to fix that
        #self._fix_type_labels()
    def _fix_type_labels(self):
        prefix_to_types = {'MONDO':[btypes.disease, btypes.named_thing, btypes.biological_entity, btypes.disease_or_phenotypic_feature],
        'EFO': [btypes.phenotypic_feature, btypes.named_thing, btypes.biological_entity, btypes.disease_or_phenotypic_feature],
        'ECTO': [btypes.chemical_exposure, btypes.exposure_event, btypes.named_thing],
        'ENVO': [btypes.environmental_feature, btypes.planetary_entity, btypes.named_thing],
        'HANCESTRO': [btypes.population_of_individual_organisms, btypes.named_thing],
        'FAO':[btypes.anatomical_entity, btypes.organismal_entity, btypes.biological_entity, btypes.named_thing],
        'PO':[btypes.anatomical_entity, btypes.organismal_entity, btypes.biological_entity, btypes.named_thing],
        'CHEBI':[btypes.chemical_substance, btypes.molecular_entity, btypes.biological_entity, btypes.named_thing],
        'HsapDv': [btypes.life_stage,btypes.organismal_entity,btypes.named_thing],
        'FOODON':[btypes.food, btypes.named_thing] }
        for gc,types in self.curie_to_type.items():
            if len(types) == 1:
                if types[0] != 'biolink:NamedThing':
                    print('seems wrong')
                    print(types[0])
                    exit()
                normcurie = self.curie_to_normalized[gc]
                prefix = normcurie.split(':')[0]
                if prefix in prefix_to_types:
                    self.curie_to_type[gc] = prefix_to_types[prefix]
                elif prefix in ['UBERON','HP','GO','CL','NCBITAXON']:
                    #This is a normalized identifier that is obsolete, or otherwise poor.
                    # Get rid of it!
                    iri = self.curie_to_iri[gc]
                    del self.iri_to_curie[iri]
                elif prefix == 'CHEBI':
                    # hardcoding some conversions to get around issues with current nn
                    chebis = {'CHEBI:26158': 'CHEBI:37848',
                              'CHEBI:22584': 'CHEBI:2762',
                              'CHEBI:185922': 'CHEBI:40154',
                              'CHEBI:2360': 'CHEBI:421707',
                              'CHEBI:153671': 'CHEBI:80240',
                              'CHEBI:64208': 'CHEBI:90693',
                              'CHEBI:4836': 'CHEBI:28792',
                              'CHEBI:33097': 'CHEBI:35227'}
                    self.curie_to_normalized[gc] = chebis[gc]
                    self.curie_to_type[gc] = prefix_to_types['CHEBI']
                elif prefix == 'HSAPDV':
                    p = gc.split(':')
                    self.curie_to_normalized[gc] = f'HsapDv:{p[1]}'
                    self.curie_to_type[gc] = prefix_to_types['HsapDv']
                else:
                    print('?')
                    #print(prefix)
                    #print(gc)
                    print(normcurie)
                    #print(self.curie_to_iri[gc])
                    #exit()
                    self.curie_to_normalized[gc]=normcurie
                    self.curie_to_type[gc]=['named_thing']
    def _remove_obsolete_terms(self):
        # If the label is "obsolete" something or another, we want to get rid of it...
        bad_curies = set()
        for curie,label in self.curie_to_label.items():
            if label.startswith('obsolete'):
                bad_curies.add(curie)
        for bc in bad_curies:
            del self.curie_to_normalized[bc]
            del self.curie_to_label[bc]
    def _remove_garbage(self):
        #The garbage curies are normalized, so I need to swap
        n2c = defaultdict(list)
        for c,n in self.curie_to_normalized.items():
            n2c[n].append(c)
        for gc in garbage_curies:
            for c in n2c[gc]:
                del self.curie_to_normalized[c]
                try:
                    del self.curie_to_label[c]
                except KeyError:
                    continue
    def _normalize_batch(self,batch):
        print(len(batch))
        result = requests.post('https://nodenormalization-sri.renci.org/get_normalized_nodes', json={'curies': batch})
        print('back')
        r = result.json()
        nb = {}
        types = {}
        labels = {}
        for b in batch:
            back = r[b]
            if r[b] is None:
                nb[b] = b
                types[b] = ['biolink:NamedThing']
            else:
                bid = r[b]['id']['identifier']
                try:
                    labels[b] = r[b]['id']['label']
                except KeyError:
                    pass
                nb[b] = bid
                types[b] = r[b]['type']
        return nb,types,labels
    def write(self,ofile):
        """Write normalized entities to a file"""
        print(f'writing to {ofile}')
        with open(ofile,'w') as outf:
            outf.write('iri\tcurie\tnormalized_curie\n')
            for iri,curie in self.iri_to_curie.items():
                try:
                    normcurie = self.curie_to_normalized[curie]
                    outf.write(f'{iri}\t{curie}\t{normcurie}\n')
                except KeyError:
                    outf.write(f'{iri}\t{curie}\t\n')

def read_accepted():
    #We look at gene matches more critically.  There are genes where ALL CAPS gene symbols should
    # be filtered out, usually b/c they are acronyms for something else
    # There are genes where non-all caps should be filtered out, either they are another word or
    # something else.  There are a few cases where particular cased versions of the symbol should
    # be accepted, and the others rejected.
    accepted_forms = defaultdict(set)
    with open('src/postprocess/gene_filters.txt','r') as inf:
        h = inf.readline()
        for line in inf:
            x = line[:-1].split('\t')
            if len(x) != 7:
                print(x)
            symbol = x[0]
            if len(x[3].strip()) == 0:
                accepted_forms[symbol].add(symbol)
            imperfect = x[5].strip()
            if len(imperfect) == 0:
                #This is a hack
                accepted_forms[symbol].add('..any..')
            elif imperfect == '/':
                oks = x[6].strip().split(' ')
                accepted_forms[symbol].update(oks)
    return accepted_forms

def normalize(indir,outdir,pmidcol=1,termcol=8,labelcol=9,cleanmatchcol=6):
    normy = Normalizer(indir)
    accepted_genes = read_accepted()
    rfiles = os.listdir(indir)
    bad_iris = set()
    for rf in rfiles:
        if not rf.endswith("tsv"):
            continue
        n = rf.split(".")[0][-4:]
        with open(f'{outdir}/annotation_0.txt','w') as outf:
            outf.write('Curie\tPaper\n')
            with open(f'{indir}/{rf}','r') as inf:
                for line in inf:
                    x = line.strip().split('\t')
                    #If this is a gene, we need to decide if this is a good annotation, or garbage
                    term = x[termcol]
                    pm_iri = x[pmidcol]
                    pmid = f"PMID:{pm_iri.split('/')[-1]}"
                    if term.startswith('https://www.genenames.org/data/gene-symbol-report/'):
                        #It's a gene
                        if labelcol is not None:
                            try:
                                label = x[labelcol].split(']')[0][1:]
                            except:
                                print('bonk')
                                print(line)
                                print(x)
                                exit()
                            text = x[cleanmatchcol][1:-1]
                            if text.upper() != label:
                                #this is stuff like 'setting' for SET.
                                #print(f'Skipping {text} {label}')
                                continue
                            if text == label and label not in accepted_genes[label]:
                                #The annotation is all caps, but all caps is not accepted for this gene.
                                #print(f'Skipping {text}')
                                continue
                            if text not in accepted_genes[label] and '..any..' not in accepted_genes[label]:
                                #print(f'Skipping {text}')
                                continue
                    #if we made it this far, it's either not a gene, or it's a gene that is really a gene.
                    curie = normy.normalize(term)
                    if (curie is not None) and (curie != ""):
                        outf.write(f'{curie}\t{pmid}\n')
                    else:
                        bad_iris.add(term)
    with open(f'{outdir}/bad_iris.txt','w') as outf:
        for bi in bad_iris:
            outf.write(f'{bi}\n')

if __name__ == '__main__':
    normalize('input','output')
