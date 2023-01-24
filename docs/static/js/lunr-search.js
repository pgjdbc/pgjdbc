window.addEventListener( "DOMContentLoaded", function ( event ) {
    let index = null;
    let lookup = null;
    let queuedTerm = null;

    let form = document.getElementById( "search" );
    let input = document.getElementById( "search-input" );
    let path = document.getElementById( "host" ).href;

    form.addEventListener( "submit", function ( event ) {
        event.preventDefault();

        let term = input.value.trim();
        if ( !term )
            return;

        startSearch( term );
    }, false );

    function startSearch( term ) {
        if ( index ) {
            // Index already present, search directly.
            search( term );
        }
        else if ( queuedTerm ) {
            // Index is being loaded, replace the term we want to search for.
            queuedTerm = term;
        }
        else {
            // Start loading index, perform the search when done.
            queuedTerm = term;
            initIndex();
        }
    }

    function searchDone() {
        queuedTerm = null;
    }

    function initIndex() {
        let request = new XMLHttpRequest();
        // console.log( request );
        request.open( "GET", `${path}/search.json` );
        request.responseType = "json";
        request.addEventListener( "load", function ( event ) {
            let documents = request.response;
            lookup = {};
            index = lunr( function () {

                this.ref( "uri" );

                this.field( "title" );
                this.field( "content" );
                this.field( "description" );
                this.field( "categories" );

                for ( let document of documents ) {
                    this.add( document );
                    lookup[document.uri] = document;
                }
            } );

            // Search index is ready, perform the search now
            search( queuedTerm );
        }, false );
        request.addEventListener( "error", searchDone, false );
        request.send( null );
    }

    function search( term ) {
        let results = index.search( term );

        // The element where search results should be displayed, adjust as needed.
        let target = document.querySelector( ".main-inner" );

        while ( target.firstChild )
            target.removeChild( target.firstChild );

        let title = document.createElement( "h1" );
        title.id = "search-results";
        title.className = "list-title";

        if ( results.length == 0 )
            title.textContent = `No results found for “${term}”`;
        else if ( results.length == 1 )
            title.textContent = `Found one result for “${term}”`;
        else
            title.textContent = `Found ${results.length} results for “${term}”`;
        target.appendChild( title );
        document.title = title.textContent;

        let template = document.getElementById( "search-result" );
        for ( let result of results ) {
            let doc = lookup[result.ref];

            // Fill out search result template, adjust as needed.
            let element = template.content.cloneNode( true );
            element.querySelector( ".summary-title-link" ).href =
                element.querySelector( ".read-more-link" ).href = doc.uri;
            element.querySelector( ".summary-title-link" ).textContent = doc.title;
            element.querySelector( ".summary" ).textContent = truncate( doc.content, 70 );
            target.appendChild( element );
        }
        title.scrollIntoView( true );

        searchDone();
    }

    // This matches Hugo's own summary logic:
    // https://github.com/gohugoio/hugo/blob/b5f39d23b8/helpers/content.go#L543
    function truncate( text, minWords ) {
        let match;
        let result = "";
        let wordCount = 0;
        let regexp = /(\S+)(\s*)/g;
        while ( match = regexp.exec( text ) ) {
            wordCount++;
            if ( wordCount <= minWords )
                result += match[0];
            else {
                let char1 = match[1][match[1].length - 1];
                let char2 = match[2][0];
                if ( /[.?!"]/.test( char1 ) || char2 == "\n" ) {
                    result += match[1];
                    break;
                }
                else
                    result += match[0];
            }
        }
        return result;
    }
}, false );