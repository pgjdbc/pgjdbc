let index = document.querySelectorAll( '.card__btn__right' );

index.forEach( ( item, key ) => {
    item.onclick = ( e ) => {
        let text = document.getElementById( `coordinates-${key}` );
        navigator.clipboard.writeText( text.innerHTML )
            .then( () => {
                item.innerHTML = "Copied!";
                item.style.backgroundColor = 'hsl(216, 70%, 64%)'
                setTimeout( () => {
                    item.innerHTML = "Copy Maven";
                    item.style.backgroundColor = 'hsl(216, 70%, 45%)'
                }, 2000 );
            } )
            .catch( ( err ) => {
                alert( 'Error in copying text: ', err );
            } )
    }
} )