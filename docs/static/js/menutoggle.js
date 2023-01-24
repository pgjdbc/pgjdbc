// Grab any element that has the 'js-toggle' class and add an event listner for the toggleClass function
let toggleBtns = document.getElementsByClassName( 'js-toggle' )
for ( let i = 0; i < toggleBtns.length; i++ ) {
    toggleBtns[i].addEventListener( 'click', toggleClass, false )
}

function toggleClass() {
    // Define the data target via the dataset "target" (e.g. data-target=".docsmenu")
    let content = this.dataset.target.split( ' ' )
    // Find any menu items that are open
    let mobileCurrentlyOpen = document.querySelector( '.mobilemenu:not(.dn)' )
    let desktopCurrentlyOpen = document.querySelector( '.desktopmenu:not(.dn)' )
    let desktopActive = document.querySelector( '.desktopmenu:not(.dn)' )

    // Loop through the targets' divs
    for ( let i = 0; i < content.length; i++ ) {
        let matches = document.querySelectorAll( content[i] );
        //for each, if the div has the 'dn' class (which is "display:none;"), remove it, otherwise, add that class
        [].forEach.call( matches, function ( dom ) {
            dom.classList.contains( 'dn' ) ?
                dom.classList.remove( 'dn' ) :
                dom.classList.add( 'dn' );
            return false;
        } );
        // close the currently open menu items
        if ( mobileCurrentlyOpen ) mobileCurrentlyOpen.classList.add( 'dn' )
        if ( desktopCurrentlyOpen ) desktopCurrentlyOpen.classList.add( 'dn' )
        if ( desktopActive ) desktopActive.classList.remove( 'db' )

    }
}
