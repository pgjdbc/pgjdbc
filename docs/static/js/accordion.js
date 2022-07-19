const items = document.querySelectorAll( ".accordion button" );

function toggleAccordion() {
    const itemToggle = this.getAttribute( 'aria-expanded' );

    for (let i = 0; i < items.length; i++ ) {
        items[i].setAttribute( 'aria-expanded', 'false' );
    }

    if ( itemToggle == 'false' ) {
        this.setAttribute( 'aria-expanded', 'true' );
    }
    console.log('clicked');
}

items.forEach( item => item.addEventListener( 'click', toggleAccordion ) );